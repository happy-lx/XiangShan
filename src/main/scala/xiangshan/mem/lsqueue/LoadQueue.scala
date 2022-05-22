/***************************************************************************************
* Copyright (c) 2020-2021 Institute of Computing Technology, Chinese Academy of Sciences
* Copyright (c) 2020-2021 Peng Cheng Laboratory
*
* XiangShan is licensed under Mulan PSL v2.
* You can use this software according to the terms and conditions of the Mulan PSL v2.
* You may obtain a copy of Mulan PSL v2 at:
*          http://license.coscl.org.cn/MulanPSL2
*
* THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
* EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
* MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
*
* See the Mulan PSL v2 for more details.
***************************************************************************************/

package xiangshan.mem

import chipsalliance.rocketchip.config.Parameters
import chisel3._
import chisel3.util._
import utils._
import xiangshan._
import xiangshan.cache._
import xiangshan.cache.{DCacheLineIO, DCacheWordIO, MemoryOpConstants}
import xiangshan.cache.mmu.TlbRequestIO
import xiangshan.mem._
import xiangshan.backend.rob.RobLsqIO
import xiangshan.backend.fu.HasExceptionNO
import xiangshan.frontend.FtqPtr
import xiangshan.backend.fu.fpu.FPU


class LqPtr(implicit p: Parameters) extends CircularQueuePtr[LqPtr](
  p => p(XSCoreParamsKey).LoadQueueSize
){
  override def cloneType = (new LqPtr).asInstanceOf[this.type]
}

object LqPtr {
  def apply(f: Bool, v: UInt)(implicit p: Parameters): LqPtr = {
    val ptr = Wire(new LqPtr)
    ptr.flag := f
    ptr.value := v
    ptr
  }
}

trait HasLoadHelper { this: XSModule =>
  def rdataHelper(uop: MicroOp, rdata: UInt): UInt = {
    val fpWen = uop.ctrl.fpWen
    LookupTree(uop.ctrl.fuOpType, List(
      LSUOpType.lb   -> SignExt(rdata(7, 0) , XLEN),
      LSUOpType.lh   -> SignExt(rdata(15, 0), XLEN),
      /*
          riscv-spec-20191213: 12.2 NaN Boxing of Narrower Values
          Any operation that writes a narrower result to an f register must write
          all 1s to the uppermost FLEN−n bits to yield a legal NaN-boxed value.
      */
      LSUOpType.lw   -> Mux(fpWen, FPU.box(rdata, FPU.S), SignExt(rdata(31, 0), XLEN)),
      LSUOpType.ld   -> Mux(fpWen, FPU.box(rdata, FPU.D), SignExt(rdata(63, 0), XLEN)),
      LSUOpType.lbu  -> ZeroExt(rdata(7, 0) , XLEN),
      LSUOpType.lhu  -> ZeroExt(rdata(15, 0), XLEN),
      LSUOpType.lwu  -> ZeroExt(rdata(31, 0), XLEN),
    ))
  }
}

class LqEnqIO(implicit p: Parameters) extends XSBundle {
  val canAccept = Output(Bool())
  val sqCanAccept = Input(Bool())
  val needAlloc = Vec(exuParameters.LsExuCnt, Input(Bool()))
  val req = Vec(exuParameters.LsExuCnt, Flipped(ValidIO(new MicroOp)))
  val resp = Vec(exuParameters.LsExuCnt, Output(new LqPtr))
}

// Load Queue
class LoadQueue(implicit p: Parameters) extends XSModule
  with HasDCacheParameters
  with HasCircularQueuePtrHelper
  with HasLoadHelper
  with HasExceptionNO
{
  val io = IO(new Bundle() {
    val enq = new LqEnqIO
    val brqRedirect = Flipped(ValidIO(new Redirect))
    val rsLoadIn = Vec(LoadPipelineWidth, Flipped(Decoupled(new LsPipelineBundle))) // load addr from rs
    val loadOut = Vec(LoadPipelineWidth, Decoupled(new LsPipelineBundle)) // select inq(rs or lq) to TLB and cache
    val loadIn = Vec(LoadPipelineWidth, Flipped(Valid(new LsPipelineBundle)))
    val storeIn = Vec(StorePipelineWidth, Flipped(Valid(new LsPipelineBundle)))
    val loadDataForwarded = Vec(LoadPipelineWidth, Input(Bool()))
    val needReplayFromRS = Vec(LoadPipelineWidth, Input(Bool()))
    val ldout = Vec(2, DecoupledIO(new ExuOutput)) // writeback int load
    val load_s1 = Vec(LoadPipelineWidth, Flipped(new PipeLoadForwardQueryIO)) // TODO: to be renamed
    val loadViolationQuery = Vec(LoadPipelineWidth, Flipped(new LoadViolationQueryIO))
    val rob = Flipped(new RobLsqIO)
    val rollback = Output(Valid(new Redirect)) // replay now starts from load instead of store
    val dcache = Flipped(ValidIO(new Refill)) // TODO: to be renamed
    val release = Flipped(ValidIO(new Release))
    val uncache = new DCacheWordIO
    val exceptionAddr = new ExceptionAddrIO
    val lqFull = Output(Bool())

    // for load retry
    val retryFast = Vec(LoadPipelineWidth, Flipped(new LoadToLsqFastIO))
    val retrySlow = Vec(LoadPipelineWidth, Flipped(new LoadToLsqSlowIO))

    val storeDataValidVec = Vec(StoreQueueSize, Input(Bool()))
  })

  println("LoadQueue: size:" + LoadQueueSize)

  val uop = Reg(Vec(LoadQueueSize, new MicroOp))
  // val data = Reg(Vec(LoadQueueSize, new LsRobEntry))
  val dataModule = Module(new LoadQueueData(LoadQueueSize, wbNumRead = LoadPipelineWidth, wbNumWrite = LoadPipelineWidth))
  dataModule.io := DontCare
  val vaddrModule = Module(new SyncDataModuleTemplate(UInt(VAddrBits.W), LoadQueueSize, numRead = 3, numWrite = LoadPipelineWidth, lastTwoReadAsy = true))
  vaddrModule.io := DontCare
  val allocated = RegInit(VecInit(List.fill(LoadQueueSize)(false.B))) // lq entry has been allocated
  val datavalid = RegInit(VecInit(List.fill(LoadQueueSize)(false.B))) // data is valid
  val writebacked = RegInit(VecInit(List.fill(LoadQueueSize)(false.B))) // inst has been writebacked to CDB
  val released = RegInit(VecInit(List.fill(LoadQueueSize)(false.B))) // load data has been released by dcache
  val miss = Reg(Vec(LoadQueueSize, Bool())) // load inst missed, waiting for miss queue to accept miss request
  // val listening = Reg(Vec(LoadQueueSize, Bool())) // waiting for refill result
  val pending = Reg(Vec(LoadQueueSize, Bool())) // mmio pending: inst is an mmio inst, it will not be executed until it reachs the end of rob
  val refilling = WireInit(VecInit(List.fill(LoadQueueSize)(false.B))) // inst has been writebacked to CDB

 /**
   * used for feedback and retry
   */

  val tlb_hited = RegInit(VecInit(List.fill(LoadQueueSize)(false.B)))
  val ld_ld_check_ok = RegInit(VecInit(List.fill(LoadQueueSize)(false.B)))

  val cache_bank_no_conflict = RegInit(VecInit(List.fill(LoadQueueSize)(false.B)))
  val cache_no_replay = RegInit(VecInit(List.fill(LoadQueueSize)(false.B)))
  val forward_data_valid = RegInit(VecInit(List.fill(LoadQueueSize)(false.B)))

 /**
   * used for re-select control
   */

  val credit = RegInit(VecInit(List.fill(LoadQueueSize)(0.U(ReSelectLen.W))))
  
  // ptrs to control which cycle to choose
  val block_ptr_tlb = RegInit(VecInit(List.fill(LoadQueueSize)(0.U(2.W))))
  val block_ptr_others = RegInit(VecInit(List.fill(LoadQueueSize)(0.U(2.W))))

  // specific cycles to block
  val block_cycles_tlb = RegInit(VecInit(Seq(1.U(ReSelectLen.W), 2.U(ReSelectLen.W), 3.U(ReSelectLen.W), 5.U(ReSelectLen.W))))
  val block_cycles_others = RegInit(VecInit(Seq(0.U(ReSelectLen.W), 0.U(ReSelectLen.W), 0.U(ReSelectLen.W), 1.U(ReSelectLen.W))))

  val sel_blocked = RegInit(VecInit(List.fill(LoadQueueSize)(false.B)))

  // data forward block
  val block_sq_idx = RegInit(VecInit(List.fill(LoadQueueSize)(0.U((log2Ceil(StoreQueueSize).W)))))
  val block_by_data_forward_fail = RegInit(VecInit(List.fill(LoadQueueSize)(false.B)))

  val creditUpdate = WireInit(VecInit(List.fill(LoadQueueSize)(0.U(ReSelectLen.W))))

  credit := creditUpdate

  (0 until LoadQueueSize).map(i => {
    creditUpdate(i) := Mux(credit(i) > 0.U(ReSelectLen.W), credit(i) - 1.U(ReSelectLen.W), credit(i))
    sel_blocked(i) := creditUpdate(i) =/= 0.U(ReSelectLen.W) || credit(i) =/= 0.U(ReSelectLen.W)
  })

  (0 until LoadQueueSize).map(i => {
    block_by_data_forward_fail(i) := Mux(block_by_data_forward_fail(i) === true.B && io.storeDataValidVec(block_sq_idx(i)) === true.B , false.B, block_by_data_forward_fail(i))
  })

  val debug_mmio = Reg(Vec(LoadQueueSize, Bool())) // mmio: inst is an mmio inst
  val debug_paddr = Reg(Vec(LoadQueueSize, UInt(PAddrBits.W))) // mmio: inst is an mmio inst

  val enqPtrExt = RegInit(VecInit((0 until io.enq.req.length).map(_.U.asTypeOf(new LqPtr))))
  val deqPtrExt = RegInit(0.U.asTypeOf(new LqPtr))
  val deqPtrExtNext = Wire(new LqPtr)
  val allowEnqueue = RegInit(true.B)

  val enqPtr = enqPtrExt(0).value
  val deqPtr = deqPtrExt.value

  val deqMask = UIntToMask(deqPtr, LoadQueueSize)
  val enqMask = UIntToMask(enqPtr, LoadQueueSize)

  val commitCount = RegNext(io.rob.lcommit)

  /**
    * Enqueue at dispatch
    *
    * Currently, LoadQueue only allows enqueue when #emptyEntries > EnqWidth
    */
  io.enq.canAccept := allowEnqueue

  for (i <- 0 until io.enq.req.length) {
    val offset = if (i == 0) 0.U else PopCount(io.enq.needAlloc.take(i))
    val lqIdx = enqPtrExt(offset)
    val index = lqIdx.value
    when (io.enq.req(i).valid && io.enq.canAccept && io.enq.sqCanAccept && !io.brqRedirect.valid) {
      uop(index) := io.enq.req(i).bits
      uop(index).lqIdx := lqIdx
      allocated(index) := true.B
      datavalid(index) := false.B
      writebacked(index) := false.B
      released(index) := false.B
      miss(index) := false.B
      // listening(index) := false.B
      pending(index) := false.B

    /**
      * used for feedback and retry
      */
      tlb_hited(index) := true.B
      ld_ld_check_ok(index) := true.B
      cache_bank_no_conflict(index) := true.B
      cache_no_replay(index) := true.B
      forward_data_valid(index) := true.B

    /**
      * used for delaying load(block-ptr to control how many cycles to block)
      */
      block_ptr_tlb(index) := 0.U(2.W)
      block_ptr_others(index) := 0.U(2.W)

      block_by_data_forward_fail(index) := false.B

    }
    io.enq.resp(i) := lqIdx
  }
  XSDebug(p"(ready, valid): ${io.enq.canAccept}, ${Binary(Cat(io.enq.req.map(_.valid)))}\n")

  // select logic 

  val ld_retry_idx_odd = WireInit(0.U(log2Ceil(LoadQueueSize).W))
  val ld_retry_idx_even = WireInit(0.U(log2Ceil(LoadQueueSize).W))

  val s0_block_load_mask = WireInit(VecInit((0 until LoadQueueSize).map(x=>false.B)))
  val s1_block_load_mask = RegNext(s0_block_load_mask)
  val s2_block_load_mask = RegNext(s1_block_load_mask)

  val lq_odd_mask = WireInit(VecInit((0 until LoadQueueSize).map(x=>{
    if(x % 2 == 0) {
      false.B
    }else {
      true.B
    }
  })))
  val lq_even_mask = WireInit(VecInit((0 until LoadQueueSize).map(x=>{
    if(x % 2 == 0) {
      true.B
    }else {
      false.B
    }
  })))

  ld_retry_idx_odd := AgePriorityEncoder((0 until LoadQueueSize).map(i => {
    val blocked = s1_block_load_mask(i) || s2_block_load_mask(i) || sel_blocked(i) || block_by_data_forward_fail(i)
    allocated(i) && !blocked && (!tlb_hited(i) || !ld_ld_check_ok(i) || !cache_bank_no_conflict(i) || !cache_no_replay(i) || !forward_data_valid(i)) && lq_odd_mask(i)
  }), deqPtr)

  ld_retry_idx_even := AgePriorityEncoder((0 until LoadQueueSize).map(i => {
    val blocked = s1_block_load_mask(i) || s2_block_load_mask(i) || sel_blocked(i) || block_by_data_forward_fail(i)
    allocated(i) && !blocked && (!tlb_hited(i) || !ld_ld_check_ok(i) || !cache_bank_no_conflict(i) || !cache_no_replay(i) || !forward_data_valid(i)) && lq_even_mask(i)
  }), deqPtr)

  vaddrModule.io.raddr(1) := ld_retry_idx_odd
  vaddrModule.io.raddr(2) := ld_retry_idx_even

  val retry_fired_odd = WireInit(false.B)
  val retry_fired_even = WireInit(false.B)

  when(retry_fired_odd) {
    s0_block_load_mask(ld_retry_idx_odd) := true.B
  }

  when(retry_fired_even) {
    s0_block_load_mask(ld_retry_idx_even) := true.B
  }

  assert(retry_fired_even && retry_fired_odd && ld_retry_idx_even =/= ld_retry_idx_odd, "can not replay same load from lq at the same time")

  def replayFromLq(replay_idx : UInt, pipeline_idx : UInt, is_odd : Boolean) = {
    val blocked = s1_block_load_mask(replay_idx) || s2_block_load_mask(replay_idx) || sel_blocked(replay_idx) || block_by_data_forward_fail(replay_idx)
    val canfire_retry = Wire(Bool())
    val vaddr = Wire(UInt(VAddrBits.W))
    if(is_odd) {
      canfire_retry := allocated(replay_idx) && !blocked && (!tlb_hited(replay_idx) || !ld_ld_check_ok(replay_idx) || !cache_bank_no_conflict(replay_idx) || !cache_no_replay(replay_idx) || !forward_data_valid(replay_idx)) && ld_retry_idx_odd(0) === 1.U
      vaddr := vaddrModule.io.rdata(1)
    }else {
      canfire_retry := allocated(replay_idx) && !blocked && (!tlb_hited(replay_idx) || !ld_ld_check_ok(replay_idx) || !cache_bank_no_conflict(replay_idx) || !cache_no_replay(replay_idx) || !forward_data_valid(replay_idx)) && ld_retry_idx_even(0) === 0.U
      vaddr := vaddrModule.io.rdata(2)
    }
    when(!io.rsLoadIn(pipeline_idx).valid && canfire_retry && io.loadOut(pipeline_idx).ready) {

      val addrAligned = LookupTree(uop(replay_idx).ctrl.fuOpType(1,0), List(
        "b00".U   -> true.B,              //b
        "b01".U   -> (io.loadOut(pipeline_idx).bits.vaddr(0) === 0.U),   //h
        "b10".U   -> (io.loadOut(pipeline_idx).bits.vaddr(1,0) === 0.U), //w
        "b11".U   -> (io.loadOut(pipeline_idx).bits.vaddr(2,0) === 0.U)  //d
      ))
      if(is_odd) {
        retry_fired_odd := true.B
      }else {
        retry_fired_even := true.B
      }
      io.loadOut(pipeline_idx).valid := true.B
      io.loadOut(pipeline_idx).bits.isLoadRetry := true.B
      // should we save uop when rs issues ?
      io.loadOut(pipeline_idx).bits.uop := uop(replay_idx)
      io.loadOut(pipeline_idx).bits.vaddr := vaddr
      io.loadOut(pipeline_idx).bits.mask := genWmask(vaddr, uop(replay_idx).ctrl.fuOpType(1,0))
      io.loadOut(pipeline_idx).bits.uop.cf.exceptionVec(loadAddrMisaligned) := !addrAligned
      }
  }

  for(i <- 0 until LoadPipelineWidth) {
    io.loadOut(i) <> io.rsLoadIn(i)

    if(i == (LoadPipelineWidth - 1)){
      val blocked = s1_block_load_mask(ld_retry_idx_odd) || s2_block_load_mask(ld_retry_idx_odd) || sel_blocked(ld_retry_idx_odd) || block_by_data_forward_fail(ld_retry_idx_odd)
      val canfire_retry = allocated(ld_retry_idx_odd) && !blocked && (!tlb_hited(ld_retry_idx_odd) || !ld_ld_check_ok(ld_retry_idx_odd) || !cache_bank_no_conflict(ld_retry_idx_odd) || !cache_no_replay(ld_retry_idx_odd) || !forward_data_valid(ld_retry_idx_odd)) && ld_retry_idx_odd(0) === 1.U
      when(!io.rsLoadIn(i).valid && canfire_retry && io.loadOut(i).ready) {

        val addrAligned = LookupTree(uop(ld_retry_idx_odd).ctrl.fuOpType(1,0), List(
          "b00".U   -> true.B,              //b
          "b01".U   -> (io.loadOut(i).bits.vaddr(0) === 0.U),   //h
          "b10".U   -> (io.loadOut(i).bits.vaddr(1,0) === 0.U), //w
          "b11".U   -> (io.loadOut(i).bits.vaddr(2,0) === 0.U)  //d
        ))

        retry_fired_odd := true.B
        io.loadOut(i).valid := true.B
        io.loadOut(i).bits.isLoadRetry := true.B
        // should we save uop when rs issues ?
        io.loadOut(i).bits.uop := uop(ld_retry_idx_odd)
        io.loadOut(i).bits.vaddr := vaddrModule.io.rdata(1)
        io.loadOut(i).bits.mask := genWmask(vaddrModule.io.rdata(1), uop(ld_retry_idx_odd).ctrl.fuOpType(1,0))
        io.loadOut(i).bits.uop.cf.exceptionVec(loadAddrMisaligned) := !addrAligned
      }
    }

    if(i == (LoadPipelineWidth - 2)) {
      val blocked = s1_block_load_mask(ld_retry_idx_even) || s2_block_load_mask(ld_retry_idx_even) || sel_blocked(ld_retry_idx_even) || block_by_data_forward_fail(ld_retry_idx_even)
      val canfire_retry = allocated(ld_retry_idx_even) && !blocked && (!tlb_hited(ld_retry_idx_even) || !ld_ld_check_ok(ld_retry_idx_even) || !cache_bank_no_conflict(ld_retry_idx_even) || !cache_no_replay(ld_retry_idx_even) || !forward_data_valid(ld_retry_idx_even)) && ld_retry_idx_even(0) === 0.U
      when(!io.rsLoadIn(i).valid && canfire_retry && io.loadOut(i).ready) {

        val addrAligned = LookupTree(uop(ld_retry_idx_even).ctrl.fuOpType(1,0), List(
          "b00".U   -> true.B,              //b
          "b01".U   -> (io.loadOut(i).bits.vaddr(0) === 0.U),   //h
          "b10".U   -> (io.loadOut(i).bits.vaddr(1,0) === 0.U), //w
          "b11".U   -> (io.loadOut(i).bits.vaddr(2,0) === 0.U)  //d
        ))

        retry_fired_even := true.B
        io.loadOut(i).valid := true.B
        io.loadOut(i).bits.isLoadRetry := true.B
        // should we save uop when rs issues ?
        io.loadOut(i).bits.uop := uop(ld_retry_idx_even)
        io.loadOut(i).bits.vaddr := vaddrModule.io.rdata(2)
        io.loadOut(i).bits.mask := genWmask(vaddrModule.io.rdata(2), uop(ld_retry_idx_even).ctrl.fuOpType(1,0))
        io.loadOut(i).bits.uop.cf.exceptionVec(loadAddrMisaligned) := !addrAligned
      }
    }

    when(io.rsLoadIn(i).valid){
      s0_block_load_mask(io.rsLoadIn(i).bits.uop.lqIdx.value) := true.B
    }
  }

  XSPerfAccumulate("load_retry_odd", retry_fired_odd)
  XSPerfAccumulate("load_retry_even", retry_fired_even)

  XSPerfAccumulate("load_tlb_miss_retry", retry_fired_odd && (tlb_hited(ld_retry_idx_odd) === false.B))
  XSPerfAccumulate("load_ld_ld_check_retry", retry_fired_odd && (ld_ld_check_ok(ld_retry_idx_odd) === false.B))
  XSPerfAccumulate("load_cache_bank_conflict_retry", retry_fired_odd && (cache_bank_no_conflict(ld_retry_idx_odd) === false.B))
  XSPerfAccumulate("load_cache_full_retry", retry_fired_odd && (cache_no_replay(ld_retry_idx_odd) === false.B))
  XSPerfAccumulate("load_data_forward_fail_retry", retry_fired_odd && (forward_data_valid(ld_retry_idx_odd) === false.B))

  XSPerfAccumulate("load_port0_unused", !io.loadOut(0).valid)
  XSPerfAccumulate("load_port0_unused_but_port1_used", !io.loadOut(0).valid && io.loadOut(1).valid)
  XSPerfAccumulate("load_willing_to_retry", PopCount(VecInit(
    (0 until LoadQueueSize).map(i => allocated(i) && (
      // can retry
      !tlb_hited(i) || !ld_ld_check_ok(i) || !cache_bank_no_conflict(i) || !cache_no_replay(i) || !forward_data_valid(i)
    ))
  )))
  XSPerfAccumulate("load_willing_to_retry_but_nacked", PopCount(VecInit(
    (0 until LoadQueueSize).map(i => allocated(i) && (
      // can retry
      !tlb_hited(i) || !ld_ld_check_ok(i) || !cache_bank_no_conflict(i) || !cache_no_replay(i) || !forward_data_valid(i)
      // not in load pipeline
    ) && (!s0_block_load_mask(i) && !s1_block_load_mask(i) && !s2_block_load_mask(i)) && retry_fired_odd
    )
  )))
  /**
    * Writeback load from load units
    *
    * Most load instructions writeback to regfile at the same time.
    * However,
    *   (1) For an mmio instruction with exceptions, it writes back to ROB immediately.
    *   (2) For an mmio instruction without exceptions, it does not write back.
    * The mmio instruction will be sent to lower level when it reaches ROB's head.
    * After uncache response, it will write back through arbiter with loadUnit.
    *   (3) For cache misses, it is marked miss and sent to dcache later.
    * After cache refills, it will write back through arbiter with loadUnit.
    */
  for (i <- 0 until LoadPipelineWidth) {
    vaddrModule.io.wen(i) := false.B
    dataModule.io.wb.wen(i) := false.B
    val loadWbIndex = io.loadIn(i).bits.uop.lqIdx.value
    when(io.loadIn(i).fire()) {
      when(io.loadIn(i).bits.miss) {
        XSInfo(io.loadIn(i).valid, "load miss write to lq idx %d pc 0x%x vaddr %x paddr %x data %x mask %x forwardData %x forwardMask: %x mmio %x\n",
          io.loadIn(i).bits.uop.lqIdx.asUInt,
          io.loadIn(i).bits.uop.cf.pc,
          io.loadIn(i).bits.vaddr,
          io.loadIn(i).bits.paddr,
          io.loadIn(i).bits.data,
          io.loadIn(i).bits.mask,
          io.loadIn(i).bits.forwardData.asUInt,
          io.loadIn(i).bits.forwardMask.asUInt,
          io.loadIn(i).bits.mmio
        )
      }.otherwise {
        XSInfo(io.loadIn(i).valid, "load hit write to cbd lqidx %d pc 0x%x vaddr %x paddr %x data %x mask %x forwardData %x forwardMask: %x mmio %x\n",
        io.loadIn(i).bits.uop.lqIdx.asUInt,
        io.loadIn(i).bits.uop.cf.pc,
        io.loadIn(i).bits.vaddr,
        io.loadIn(i).bits.paddr,
        io.loadIn(i).bits.data,
        io.loadIn(i).bits.mask,
        io.loadIn(i).bits.forwardData.asUInt,
        io.loadIn(i).bits.forwardMask.asUInt,
        io.loadIn(i).bits.mmio
      )}
      datavalid(loadWbIndex) := (!io.loadIn(i).bits.miss || io.loadDataForwarded(i)) &&
        !io.loadIn(i).bits.mmio && // mmio data is not valid until we finished uncache access
        !io.needReplayFromRS(i) // do not writeback if that inst will be resend from rs
      writebacked(loadWbIndex) := !io.loadIn(i).bits.miss && !io.loadIn(i).bits.mmio

      val loadWbData = Wire(new LQDataEntry)
      loadWbData.paddr := io.loadIn(i).bits.paddr
      loadWbData.mask := io.loadIn(i).bits.mask
      loadWbData.data := io.loadIn(i).bits.forwardData.asUInt // fwd data
      loadWbData.fwdMask := io.loadIn(i).bits.forwardMask
      dataModule.io.wbWrite(i, loadWbIndex, loadWbData)
      dataModule.io.wb.wen(i) := true.B


      debug_mmio(loadWbIndex) := io.loadIn(i).bits.mmio
      debug_paddr(loadWbIndex) := io.loadIn(i).bits.paddr

      val dcacheMissed = io.loadIn(i).bits.miss && !io.loadIn(i).bits.mmio
      miss(loadWbIndex) := dcacheMissed && !io.loadDataForwarded(i) && !io.needReplayFromRS(i)
      pending(loadWbIndex) := io.loadIn(i).bits.mmio
      uop(loadWbIndex).debugInfo := io.loadIn(i).bits.uop.debugInfo
      // update replayInst (replay from fetch) bit, 
      // for replayInst may be set to true in load pipeline
      uop(loadWbIndex).ctrl.replayInst := io.loadIn(i).bits.uop.ctrl.replayInst

    }

    when(io.rsLoadIn(i).valid && io.loadOut(i).ready){
      val rsLdWbIndex = io.rsLoadIn(i).bits.uop.lqIdx.value
      
      vaddrModule.io.waddr(i) := rsLdWbIndex
      vaddrModule.io.wdata(i) := io.rsLoadIn(i).bits.vaddr
      vaddrModule.io.wen(i) := true.B

      // TODO: fix me 
      // uop(rsLdWbIndex) := io.rsLoadIn(i).bits.uop
    }

    /**
      * used for feedback and retry
      */
    when(io.retryFast(i).valid){
      val idx = io.retryFast(i).ld_idx
      val needretry = !io.retryFast(i).ld_ld_check_ok || !io.retryFast(i).cache_bank_no_conflict
      
      ld_ld_check_ok(idx) := io.retryFast(i).ld_ld_check_ok
      cache_bank_no_conflict(idx) := io.retryFast(i).cache_bank_no_conflict

      when(needretry) {
        creditUpdate(idx) := block_cycles_others(block_ptr_others(idx))
        block_ptr_others(idx) := Mux(block_ptr_others(idx) === 3.U(2.W), block_ptr_others(idx), block_ptr_others(idx) + 1.U(2.W))
      }
    }

    when(io.retrySlow(i).valid){
      val idx = io.retrySlow(i).ld_idx
      val needretry = !io.retrySlow(i).tlb_hited || !io.retrySlow(i).cache_no_replay || !io.retrySlow(i).forward_data_valid

      tlb_hited(idx) := io.retrySlow(i).tlb_hited
      cache_no_replay(idx) := io.retrySlow(i).cache_no_replay
      forward_data_valid(idx) := io.retrySlow(i).forward_data_valid

      val invalid_sq_idx = io.retrySlow(i).data_invalid_sq_idx

      when(needretry) {
        creditUpdate(idx) := Mux( !io.retrySlow(i).tlb_hited, block_cycles_tlb(block_ptr_tlb(idx)), Mux(!io.retrySlow(i).cache_no_replay, block_cycles_others(block_ptr_others(idx)), 0.U))
        when(!io.retrySlow(i).tlb_hited) {
          block_ptr_tlb(idx) := Mux(block_ptr_tlb(idx) === 3.U(2.W), block_ptr_tlb(idx), block_ptr_tlb(idx) + 1.U(2.W))
        }.elsewhen(!io.retrySlow(i).cache_no_replay) {
          block_ptr_others(idx) := Mux(block_ptr_others(idx) === 3.U(2.W), block_ptr_others(idx), block_ptr_others(idx) + 1.U(2.W))
        }
      }
      
      block_by_data_forward_fail(idx) := false.B

      when(!io.retrySlow(i).forward_data_valid) {
        when(!io.storeDataValidVec(invalid_sq_idx)) {
          block_by_data_forward_fail(idx) := true.B
          block_sq_idx(idx) := invalid_sq_idx
        }
      }
    }
    // vaddrModule write is delayed, as vaddrModule will not be read right after write
    // vaddrModule.io.waddr(i) := RegNext(loadWbIndex)
    // vaddrModule.io.wdata(i) := RegNext(io.loadIn(i).bits.vaddr)
    // vaddrModule.io.wen(i) := RegNext(io.loadIn(i).fire())
  }

  when(io.dcache.valid) {
    XSDebug("miss resp: paddr:0x%x data %x\n", io.dcache.bits.addr, io.dcache.bits.data)
  }

  // Refill 64 bit in a cycle
  // Refill data comes back from io.dcache.resp
  // 好像是refill cache miss的东西
  dataModule.io.refill.valid := io.dcache.valid
  dataModule.io.refill.paddr := io.dcache.bits.addr
  dataModule.io.refill.data := io.dcache.bits.data

  (0 until LoadQueueSize).map(i => {
    dataModule.io.refill.refillMask(i) := allocated(i) && miss(i)
    when(dataModule.io.refill.valid && dataModule.io.refill.refillMask(i) && dataModule.io.refill.matchMask(i)) {
      datavalid(i) := true.B
      miss(i) := false.B
      refilling(i) := true.B
    }
  })

  // Writeback up to 2 missed load insts to CDB
  //
  // Pick 2 missed load (data refilled), write them back to cdb
  // 2 refilled load will be selected from even/odd entry, separately

  // Stage 0
  // Generate writeback indexes

  def getEvenBits(input: UInt): UInt = {
    VecInit((0 until LoadQueueSize/2).map(i => {input(2*i)})).asUInt
  }
  def getOddBits(input: UInt): UInt = {
    VecInit((0 until LoadQueueSize/2).map(i => {input(2*i+1)})).asUInt
  }

  val loadWbSel = Wire(Vec(LoadPipelineWidth, UInt(log2Up(LoadQueueSize).W))) // index selected last cycle
  val loadWbSelV = Wire(Vec(LoadPipelineWidth, Bool())) // index selected in last cycle is valid

  val loadWbSelVec = VecInit((0 until LoadQueueSize).map(i => {
    allocated(i) && !writebacked(i) && (datavalid(i) || refilling(i))
  })).asUInt() // use uint instead vec to reduce verilog lines
  val evenDeqMask = getEvenBits(deqMask)
  val oddDeqMask = getOddBits(deqMask)
  // generate lastCycleSelect mask
  val evenSelectMask = Mux(io.ldout(0).fire(), getEvenBits(UIntToOH(loadWbSel(0))), 0.U)
  val oddSelectMask = Mux(io.ldout(1).fire(), getOddBits(UIntToOH(loadWbSel(1))), 0.U)
  // generate real select vec
  val loadEvenSelVec = getEvenBits(loadWbSelVec) & ~evenSelectMask
  val loadOddSelVec = getOddBits(loadWbSelVec) & ~oddSelectMask

  def toVec(a: UInt): Vec[Bool] = {
    VecInit(a.asBools)
  }

  val loadWbSelGen = Wire(Vec(LoadPipelineWidth, UInt(log2Up(LoadQueueSize).W)))
  val loadWbSelVGen = Wire(Vec(LoadPipelineWidth, Bool()))
  loadWbSelGen(0) := Cat(getFirstOne(toVec(loadEvenSelVec), evenDeqMask), 0.U(1.W))
  loadWbSelVGen(0):= loadEvenSelVec.asUInt.orR
  loadWbSelGen(1) := Cat(getFirstOne(toVec(loadOddSelVec), oddDeqMask), 1.U(1.W))
  loadWbSelVGen(1) := loadOddSelVec.asUInt.orR

  (0 until LoadPipelineWidth).map(i => {
    loadWbSel(i) := RegNext(loadWbSelGen(i))
    loadWbSelV(i) := RegNext(loadWbSelVGen(i), init = false.B)
    when(io.ldout(i).fire()){
      // Mark them as writebacked, so they will not be selected in the next cycle
      writebacked(loadWbSel(i)) := true.B
    }
  })

  // Stage 1
  // Use indexes generated in cycle 0 to read data
  // writeback data to cdb
  (0 until LoadPipelineWidth).map(i => {
    // data select
    dataModule.io.wb.raddr(i) := loadWbSelGen(i)
    val rdata = dataModule.io.wb.rdata(i).data
    val seluop = uop(loadWbSel(i))
    val func = seluop.ctrl.fuOpType
    val raddr = dataModule.io.wb.rdata(i).paddr
    val rdataSel = LookupTree(raddr(2, 0), List(
      "b000".U -> rdata(63, 0),
      "b001".U -> rdata(63, 8),
      "b010".U -> rdata(63, 16),
      "b011".U -> rdata(63, 24),
      "b100".U -> rdata(63, 32),
      "b101".U -> rdata(63, 40),
      "b110".U -> rdata(63, 48),
      "b111".U -> rdata(63, 56)
    ))
    val rdataPartialLoad = rdataHelper(seluop, rdataSel)

    // writeback missed int/fp load
    //
    // Int load writeback will finish (if not blocked) in one cycle
    io.ldout(i).bits.uop := seluop
    io.ldout(i).bits.uop.lqIdx := loadWbSel(i).asTypeOf(new LqPtr)
    io.ldout(i).bits.data := rdataPartialLoad
    io.ldout(i).bits.redirectValid := false.B
    io.ldout(i).bits.redirect := DontCare
    io.ldout(i).bits.debug.isMMIO := debug_mmio(loadWbSel(i))
    io.ldout(i).bits.debug.isPerfCnt := false.B
    io.ldout(i).bits.debug.paddr := debug_paddr(loadWbSel(i))
    io.ldout(i).bits.fflags := DontCare
    io.ldout(i).valid := loadWbSelV(i)

    when(io.ldout(i).fire()) {
      XSInfo("int load miss write to cbd robidx %d lqidx %d pc 0x%x mmio %x\n",
        io.ldout(i).bits.uop.robIdx.asUInt,
        io.ldout(i).bits.uop.lqIdx.asUInt,
        io.ldout(i).bits.uop.cf.pc,
        debug_mmio(loadWbSel(i))
      )
    }

  })

  /**
    * Load commits
    *
    * When load commited, mark it as !allocated and move deqPtrExt forward.
    */
  (0 until CommitWidth).map(i => {
    when(commitCount > i.U){
      allocated((deqPtrExt+i.U).value) := false.B
    }
  })

  def getFirstOne(mask: Vec[Bool], startMask: UInt) = {
    val length = mask.length
    val highBits = (0 until length).map(i => mask(i) & ~startMask(i))
    val highBitsUint = Cat(highBits.reverse)
    PriorityEncoder(Mux(highBitsUint.orR(), highBitsUint, mask.asUInt))
  }

  def getOldestInTwo(valid: Seq[Bool], uop: Seq[MicroOp]) = {
    assert(valid.length == uop.length)
    assert(valid.length == 2)
    Mux(valid(0) && valid(1),
      Mux(isAfter(uop(0).robIdx, uop(1).robIdx), uop(1), uop(0)),
      Mux(valid(0) && !valid(1), uop(0), uop(1)))
  }

  def getAfterMask(valid: Seq[Bool], uop: Seq[MicroOp]) = {
    assert(valid.length == uop.length)
    val length = valid.length
    (0 until length).map(i => {
      (0 until length).map(j => {
        Mux(valid(i) && valid(j),
          isAfter(uop(i).robIdx, uop(j).robIdx),
          Mux(!valid(i), true.B, false.B))
      })
    })
  }

  /**
    * Store-Load Memory violation detection
    *
    * When store writes back, it searches LoadQueue for younger load instructions
    * with the same load physical address. They loaded wrong data and need re-execution.
    *
    * Cycle 0: Store Writeback
    *   Generate match vector for store address with rangeMask(stPtr, enqPtr).
    *   Besides, load instructions in LoadUnit_S1 and S2 are also checked.
    * Cycle 1: Redirect Generation
    *   There're three possible types of violations, up to 6 possible redirect requests.
    *   Choose the oldest load (part 1). (4 + 2) -> (1 + 2)
    * Cycle 2: Redirect Fire
    *   Choose the oldest load (part 2). (3 -> 1)
    *   Prepare redirect request according to the detected violation.
    *   Fire redirect request (if valid)
    */

  // stage 0:        lq l1 wb     l1 wb lq
  //                 |  |  |      |  |  |  (paddr match)
  // stage 1:        lq l1 wb     l1 wb lq
  //                 |  |  |      |  |  |
  //                 |  |------------|  |
  //                 |        |         |
  // stage 2:        lq      l1wb       lq
  //                 |        |         |
  //                 --------------------
  //                          |
  //                      rollback req
  io.load_s1 := DontCare
  def detectRollback(i: Int) = {
    val startIndex = io.storeIn(i).bits.uop.lqIdx.value
    val lqIdxMask = UIntToMask(startIndex, LoadQueueSize)
    val xorMask = lqIdxMask ^ enqMask
    val sameFlag = io.storeIn(i).bits.uop.lqIdx.flag === enqPtrExt(0).flag
    val toEnqPtrMask = Mux(sameFlag, xorMask, ~xorMask)

    // check if load already in lq needs to be rolledback
    dataModule.io.violation(i).paddr := io.storeIn(i).bits.paddr
    dataModule.io.violation(i).mask := io.storeIn(i).bits.mask
    val addrMaskMatch = RegNext(dataModule.io.violation(i).violationMask)
    val entryNeedCheck = RegNext(VecInit((0 until LoadQueueSize).map(j => {
      allocated(j) && toEnqPtrMask(j) && (datavalid(j) || miss(j))
    })))
    val lqViolationVec = VecInit((0 until LoadQueueSize).map(j => {
      addrMaskMatch(j) && entryNeedCheck(j)
    }))
    val lqViolation = lqViolationVec.asUInt().orR()
    val lqViolationIndex = getFirstOne(lqViolationVec, RegNext(lqIdxMask))
    val lqViolationUop = uop(lqViolationIndex)
    // lqViolationUop.lqIdx.flag := deqMask(lqViolationIndex) ^ deqPtrExt.flag
    // lqViolationUop.lqIdx.value := lqViolationIndex
    XSDebug(lqViolation, p"${Binary(Cat(lqViolationVec))}, $startIndex, $lqViolationIndex\n")

    // when l/s writeback to rob together, check if rollback is needed
    val wbViolationVec = RegNext(VecInit((0 until LoadPipelineWidth).map(j => {
      io.loadIn(j).valid &&
        isAfter(io.loadIn(j).bits.uop.robIdx, io.storeIn(i).bits.uop.robIdx) &&
        io.storeIn(i).bits.paddr(PAddrBits - 1, 3) === io.loadIn(j).bits.paddr(PAddrBits - 1, 3) &&
        (io.storeIn(i).bits.mask & io.loadIn(j).bits.mask).orR
    })))
    val wbViolation = wbViolationVec.asUInt().orR()
    val wbViolationUop = getOldestInTwo(wbViolationVec, RegNext(VecInit(io.loadIn.map(_.bits.uop))))
    XSDebug(wbViolation, p"${Binary(Cat(wbViolationVec))}, $wbViolationUop\n")

    // check if rollback is needed for load in l1
    val l1ViolationVec = RegNext(VecInit((0 until LoadPipelineWidth).map(j => {
      io.load_s1(j).valid && // L1 valid
        isAfter(io.load_s1(j).uop.robIdx, io.storeIn(i).bits.uop.robIdx) &&
        io.storeIn(i).bits.paddr(PAddrBits - 1, 3) === io.load_s1(j).paddr(PAddrBits - 1, 3) &&
        (io.storeIn(i).bits.mask & io.load_s1(j).mask).orR
    })))
    val l1Violation = l1ViolationVec.asUInt().orR()
    val l1ViolationUop = getOldestInTwo(l1ViolationVec, RegNext(VecInit(io.load_s1.map(_.uop))))
    XSDebug(l1Violation, p"${Binary(Cat(l1ViolationVec))}, $l1ViolationUop\n")

    XSDebug(
      l1Violation,
      "need rollback (l1 load) pc %x robidx %d target %x\n",
      io.storeIn(i).bits.uop.cf.pc, io.storeIn(i).bits.uop.robIdx.asUInt, l1ViolationUop.robIdx.asUInt
    )
    XSDebug(
      lqViolation,
      "need rollback (ld wb before store) pc %x robidx %d target %x\n",
      io.storeIn(i).bits.uop.cf.pc, io.storeIn(i).bits.uop.robIdx.asUInt, lqViolationUop.robIdx.asUInt
    )
    XSDebug(
      wbViolation,
      "need rollback (ld/st wb together) pc %x robidx %d target %x\n",
      io.storeIn(i).bits.uop.cf.pc, io.storeIn(i).bits.uop.robIdx.asUInt, wbViolationUop.robIdx.asUInt
    )

    ((lqViolation, lqViolationUop), (wbViolation, wbViolationUop), (l1Violation, l1ViolationUop))
  }

  def rollbackSel(a: Valid[MicroOpRbExt], b: Valid[MicroOpRbExt]): ValidIO[MicroOpRbExt] = {
    Mux(
      a.valid,
      Mux(
        b.valid,
        Mux(isAfter(a.bits.uop.robIdx, b.bits.uop.robIdx), b, a), // a,b both valid, sel oldest
        a // sel a
      ),
      b // sel b
    )
  }
  val lastCycleRedirect = RegNext(io.brqRedirect)
  val lastlastCycleRedirect = RegNext(lastCycleRedirect)

  // S2: select rollback (part1) and generate rollback request
  // rollback check
  // Wb/L1 rollback seq check is done in s2
  val rollbackWb = Wire(Vec(StorePipelineWidth, Valid(new MicroOpRbExt)))
  val rollbackL1 = Wire(Vec(StorePipelineWidth, Valid(new MicroOpRbExt)))
  val rollbackL1Wb = Wire(Vec(StorePipelineWidth*2, Valid(new MicroOpRbExt)))
  // Lq rollback seq check is done in s3 (next stage), as getting rollbackLq MicroOp is slow
  val rollbackLq = Wire(Vec(StorePipelineWidth, Valid(new MicroOpRbExt)))
  // store ftq index for store set update
  val stFtqIdxS2 = Wire(Vec(StorePipelineWidth, new FtqPtr))
  val stFtqOffsetS2 = Wire(Vec(StorePipelineWidth, UInt(log2Up(PredictWidth).W)))
  for (i <- 0 until StorePipelineWidth) {
    val detectedRollback = detectRollback(i)
    rollbackLq(i).valid := detectedRollback._1._1 && RegNext(io.storeIn(i).valid)
    rollbackLq(i).bits.uop := detectedRollback._1._2
    rollbackLq(i).bits.flag := i.U
    rollbackWb(i).valid := detectedRollback._2._1 && RegNext(io.storeIn(i).valid)
    rollbackWb(i).bits.uop := detectedRollback._2._2
    rollbackWb(i).bits.flag := i.U
    rollbackL1(i).valid := detectedRollback._3._1 && RegNext(io.storeIn(i).valid)
    rollbackL1(i).bits.uop := detectedRollback._3._2
    rollbackL1(i).bits.flag := i.U
    rollbackL1Wb(2*i) := rollbackL1(i)
    rollbackL1Wb(2*i+1) := rollbackWb(i)
    stFtqIdxS2(i) := RegNext(io.storeIn(i).bits.uop.cf.ftqPtr)
    stFtqOffsetS2(i) := RegNext(io.storeIn(i).bits.uop.cf.ftqOffset)
  }

  val rollbackL1WbSelected = ParallelOperation(rollbackL1Wb, rollbackSel)
  val rollbackL1WbVReg = RegNext(rollbackL1WbSelected.valid)
  val rollbackL1WbReg = RegEnable(rollbackL1WbSelected.bits, rollbackL1WbSelected.valid)
  val rollbackLq0VReg = RegNext(rollbackLq(0).valid)
  val rollbackLq0Reg = RegEnable(rollbackLq(0).bits, rollbackLq(0).valid)
  val rollbackLq1VReg = RegNext(rollbackLq(1).valid)
  val rollbackLq1Reg = RegEnable(rollbackLq(1).bits, rollbackLq(1).valid)

  // S3: select rollback (part2), generate rollback request, then fire rollback request
  // Note that we use robIdx - 1.U to flush the load instruction itself.
  // Thus, here if last cycle's robIdx equals to this cycle's robIdx, it still triggers the redirect.

  // FIXME: this is ugly
  val rollbackValidVec = Seq(rollbackL1WbVReg, rollbackLq0VReg, rollbackLq1VReg)
  val rollbackUopExtVec = Seq(rollbackL1WbReg, rollbackLq0Reg, rollbackLq1Reg)

  // select uop in parallel
  val mask = getAfterMask(rollbackValidVec, rollbackUopExtVec.map(i => i.uop))
  val oneAfterZero = mask(1)(0)
  val rollbackUopExt = Mux(oneAfterZero && mask(2)(0),
    rollbackUopExtVec(0),
    Mux(!oneAfterZero && mask(2)(1), rollbackUopExtVec(1), rollbackUopExtVec(2)))
  val stFtqIdxS3 = RegNext(stFtqIdxS2)
  val stFtqOffsetS3 = RegNext(stFtqOffsetS2)
  val rollbackUop = rollbackUopExt.uop
  val rollbackStFtqIdx = stFtqIdxS3(rollbackUopExt.flag)
  val rollbackStFtqOffset = stFtqOffsetS3(rollbackUopExt.flag)

  // check if rollback request is still valid in parallel
  val rollbackValidVecChecked = Wire(Vec(3, Bool()))
  for(((v, uop), idx) <- rollbackValidVec.zip(rollbackUopExtVec.map(i => i.uop)).zipWithIndex) {
    rollbackValidVecChecked(idx) := v &&
      (!lastCycleRedirect.valid || isBefore(uop.robIdx, lastCycleRedirect.bits.robIdx)) &&
      (!lastlastCycleRedirect.valid || isBefore(uop.robIdx, lastlastCycleRedirect.bits.robIdx))
  }

  io.rollback.bits.robIdx := rollbackUop.robIdx
  io.rollback.bits.ftqIdx := rollbackUop.cf.ftqPtr
  io.rollback.bits.stFtqIdx := rollbackStFtqIdx
  io.rollback.bits.ftqOffset := rollbackUop.cf.ftqOffset
  io.rollback.bits.stFtqOffset := rollbackStFtqOffset
  io.rollback.bits.level := RedirectLevel.flush
  io.rollback.bits.interrupt := DontCare
  io.rollback.bits.cfiUpdate := DontCare
  io.rollback.bits.cfiUpdate.target := rollbackUop.cf.pc
  io.rollback.bits.debug_runahead_checkpoint_id := rollbackUop.debugInfo.runahead_checkpoint_id
  // io.rollback.bits.pc := DontCare

  io.rollback.valid := rollbackValidVecChecked.asUInt.orR

  when(io.rollback.valid) {
    // XSDebug("Mem rollback: pc %x robidx %d\n", io.rollback.bits.cfi, io.rollback.bits.robIdx.asUInt)
  }

  /**
  * Load-Load Memory violation detection
  *
  * When load arrives load_s1, it searches LoadQueue for younger load instructions
  * with the same load physical address. If younger load has been released (or observed),
  * the younger load needs to be re-execed.
  * 
  * For now, if re-exec it found to be needed in load_s1, we mark the older load as replayInst,
  * the two loads will be replayed if the older load becomes the head of rob.
  *
  * When dcache releases a line, mark all writebacked entrys in load queue with
  * the same line paddr as released.
  */

  // Load-Load Memory violation query
  val deqRightMask = UIntToMask.rightmask(deqPtr, LoadQueueSize)
  (0 until LoadPipelineWidth).map(i => {
    dataModule.io.release_violation(i).paddr := io.loadViolationQuery(i).req.bits.paddr
    io.loadViolationQuery(i).req.ready := true.B
    io.loadViolationQuery(i).resp.valid := RegNext(io.loadViolationQuery(i).req.fire())
    // Generate real violation mask
    // Note that we use UIntToMask.rightmask here
    val startIndex = io.loadViolationQuery(i).req.bits.uop.lqIdx.value
    val lqIdxMask = UIntToMask.rightmask(startIndex, LoadQueueSize)
    val xorMask = lqIdxMask ^ deqRightMask
    val sameFlag = io.loadViolationQuery(i).req.bits.uop.lqIdx.flag === deqPtrExt.flag
    val toDeqPtrMask = Mux(sameFlag, xorMask, ~xorMask)
    val ldld_violation_mask = WireInit(VecInit((0 until LoadQueueSize).map(j => {
      dataModule.io.release_violation(i).match_mask(j) && // addr match
      toDeqPtrMask(j) && // the load is younger than current load
      allocated(j) && // entry is valid
      released(j) && // cacheline is released
      (datavalid(j) || miss(j)) // paddr is valid
    })))
    dontTouch(ldld_violation_mask)
    ldld_violation_mask.suggestName("ldldViolationMask_" + i)
    io.loadViolationQuery(i).resp.bits.have_violation := RegNext(ldld_violation_mask.asUInt.orR)
  })

  // "released" flag update
  // 
  // When io.release.valid, it uses the last ld-ld paddr cam port to
  // update release flag in 1 cycle
  when(io.release.valid){
    // Take over ld-ld paddr cam port
    dataModule.io.release_violation.takeRight(1)(0).paddr := io.release.bits.paddr
    io.loadViolationQuery.takeRight(1)(0).req.ready := false.B
    // If a load needs that cam port, replay it from rs
    (0 until LoadQueueSize).map(i => {
      when(dataModule.io.release_violation.takeRight(1)(0).match_mask(i) && allocated(i) && writebacked(i)){
        // Note: if a load has missed in dcache and is waiting for refill in load queue,
        // its released flag still needs to be set as true if addr matches. 
        released(i) := true.B
      }
    })
  }

  /**
    * Memory mapped IO / other uncached operations
    *
    * States:
    * (1) writeback from store units: mark as pending
    * (2) when they reach ROB's head, they can be sent to uncache channel
    * (3) response from uncache channel: mark as datavalid
    * (4) writeback to ROB (and other units): mark as writebacked
    * (5) ROB commits the instruction: same as normal instructions
    */
  //(2) when they reach ROB's head, they can be sent to uncache channel
  val lqTailMmioPending = WireInit(pending(deqPtr))
  val lqTailAllocated = WireInit(allocated(deqPtr))
  val s_idle :: s_req :: s_resp :: s_wait :: Nil = Enum(4)
  val uncacheState = RegInit(s_idle)
  switch(uncacheState) {
    is(s_idle) {
      when(io.rob.pendingld && lqTailMmioPending && lqTailAllocated) {
        uncacheState := s_req
      }
    }
    is(s_req) {
      when(io.uncache.req.fire()) {
        uncacheState := s_resp
      }
    }
    is(s_resp) {
      when(io.uncache.resp.fire()) {
        uncacheState := s_wait
      }
    }
    is(s_wait) {
      when(io.rob.commit) {
        uncacheState := s_idle // ready for next mmio
      }
    }
  }
  io.uncache.req.valid := uncacheState === s_req

  dataModule.io.uncache.raddr := deqPtrExtNext.value

  io.uncache.req.bits.cmd  := MemoryOpConstants.M_XRD
  io.uncache.req.bits.addr := dataModule.io.uncache.rdata.paddr
  io.uncache.req.bits.data := dataModule.io.uncache.rdata.data
  io.uncache.req.bits.mask := dataModule.io.uncache.rdata.mask

  io.uncache.req.bits.id   := DontCare
  io.uncache.req.bits.instrtype := DontCare

  io.uncache.resp.ready := true.B

  when (io.uncache.req.fire()) {
    pending(deqPtr) := false.B

    XSDebug("uncache req: pc %x addr %x data %x op %x mask %x\n",
      uop(deqPtr).cf.pc,
      io.uncache.req.bits.addr,
      io.uncache.req.bits.data,
      io.uncache.req.bits.cmd,
      io.uncache.req.bits.mask
    )
  }

  // (3) response from uncache channel: mark as datavalid
  dataModule.io.uncache.wen := false.B
  when(io.uncache.resp.fire()){
    datavalid(deqPtr) := true.B
    dataModule.io.uncacheWrite(deqPtr, io.uncache.resp.bits.data(XLEN-1, 0))
    dataModule.io.uncache.wen := true.B

    XSDebug("uncache resp: data %x\n", io.dcache.bits.data)
  }

  // Read vaddr for mem exception
  // no inst will be commited 1 cycle before tval update
  vaddrModule.io.raddr(0) := (deqPtrExt + commitCount).value
  io.exceptionAddr.vaddr := vaddrModule.io.rdata(0)

  // misprediction recovery / exception redirect
  // invalidate lq term using robIdx
  val needCancel = Wire(Vec(LoadQueueSize, Bool()))
  for (i <- 0 until LoadQueueSize) {
    needCancel(i) := uop(i).robIdx.needFlush(io.brqRedirect) && allocated(i)
    when (needCancel(i)) {
        allocated(i) := false.B
    }
  }

  /**
    * update pointers
    */
  val lastCycleCancelCount = PopCount(RegNext(needCancel))
  // when io.brqRedirect.valid, we don't allow eneuque even though it may fire.
  val enqNumber = Mux(io.enq.canAccept && io.enq.sqCanAccept && !io.brqRedirect.valid, PopCount(io.enq.req.map(_.valid)), 0.U)
  when (lastCycleRedirect.valid) {
    // we recover the pointers in the next cycle after redirect
    enqPtrExt := VecInit(enqPtrExt.map(_ - lastCycleCancelCount))
  }.otherwise {
    enqPtrExt := VecInit(enqPtrExt.map(_ + enqNumber))
  }

  deqPtrExtNext := deqPtrExt + commitCount
  deqPtrExt := deqPtrExtNext

  val validCount = distanceBetween(enqPtrExt(0), deqPtrExt)

  allowEnqueue := validCount + enqNumber <= (LoadQueueSize - io.enq.req.length).U

  /**
    * misc
    */
  io.rob.storeDataRobWb := DontCare // will be overwriten by store queue's result

  // perf counter
  QueuePerf(LoadQueueSize, validCount, !allowEnqueue)
  io.lqFull := !allowEnqueue
  XSPerfAccumulate("rollback", io.rollback.valid) // rollback redirect generated
  XSPerfAccumulate("mmioCycle", uncacheState =/= s_idle) // lq is busy dealing with uncache req
  XSPerfAccumulate("mmioCnt", io.uncache.req.fire())
  XSPerfAccumulate("refill", io.dcache.valid)
  XSPerfAccumulate("writeback_success", PopCount(VecInit(io.ldout.map(i => i.fire()))))
  XSPerfAccumulate("writeback_blocked", PopCount(VecInit(io.ldout.map(i => i.valid && !i.ready))))
  XSPerfAccumulate("utilization_miss", PopCount((0 until LoadQueueSize).map(i => allocated(i) && miss(i))))

  val perfinfo = IO(new Bundle(){
    val perfEvents = Output(new PerfEventsBundle(10))
  })
  val perfEvents = Seq(
    ("rollback          ", io.rollback.valid                                                               ),
    ("mmioCycle         ", uncacheState =/= s_idle                                                         ),
    ("mmio_Cnt          ", io.uncache.req.fire()                                                           ),
    ("refill            ", io.dcache.valid                                                                 ),
    ("writeback_success ", PopCount(VecInit(io.ldout.map(i => i.fire())))                                  ),
    ("writeback_blocked ", PopCount(VecInit(io.ldout.map(i => i.valid && !i.ready)))                       ),
    ("ltq_1/4_valid     ", (validCount < (LoadQueueSize.U/4.U))                                            ),
    ("ltq_2/4_valid     ", (validCount > (LoadQueueSize.U/4.U)) & (validCount <= (LoadQueueSize.U/2.U))    ),
    ("ltq_3/4_valid     ", (validCount > (LoadQueueSize.U/2.U)) & (validCount <= (LoadQueueSize.U*3.U/4.U))),
    ("ltq_4/4_valid     ", (validCount > (LoadQueueSize.U*3.U/4.U))                                        ),
  )

  for (((perf_out,(perf_name,perf)),i) <- perfinfo.perfEvents.perf_events.zip(perfEvents).zipWithIndex) {
    perf_out.incr_step := RegNext(perf)
  }
  // debug info
  XSDebug("enqPtrExt %d:%d deqPtrExt %d:%d\n", enqPtrExt(0).flag, enqPtr, deqPtrExt.flag, deqPtr)

  def PrintFlag(flag: Bool, name: String): Unit = {
    when(flag) {
      XSDebug(false, true.B, name)
    }.otherwise {
      XSDebug(false, true.B, " ")
    }
  }

  for (i <- 0 until LoadQueueSize) {
    XSDebug(i + " pc %x pa %x ", uop(i).cf.pc, debug_paddr(i))
    PrintFlag(allocated(i), "a")
    PrintFlag(allocated(i) && datavalid(i), "v")
    PrintFlag(allocated(i) && writebacked(i), "w")
    PrintFlag(allocated(i) && miss(i), "m")
    PrintFlag(allocated(i) && pending(i), "p")
    XSDebug(false, true.B, "\n")
  }

}
