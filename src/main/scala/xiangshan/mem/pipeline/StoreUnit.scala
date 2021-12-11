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
import xiangshan.backend.decode.ImmUnion
import xiangshan.backend.fu.PMPRespBundle
import xiangshan.cache._
import xiangshan.cache.mmu.{TLB, TlbCmd, TlbPtwIO, TlbReq, TlbRequestIO, TlbResp}

// Store Pipeline Stage 0
// Generate addr, use addr to query DCache and DTLB
class StoreUnit_S0(implicit p: Parameters) extends XSModule {
  val io = IO(new Bundle() {
    val in = Flipped(Decoupled(new ExuInput))
    val rsIdx = Input(UInt(log2Up(IssQueSize).W))
    val isFirstIssue = Input(Bool())
    // wire from store pipeline to sq
    val lsqIn = Decoupled(new LsPipelineBundle)
    // wire from sq to store pipeline
    val lsqOut = Flipped(Decoupled(new LsPipelineBundle))
    val out = Decoupled(new LsPipelineBundle)
  })

  // send req to dtlb
  // val saddr = io.in.bits.src(0) + SignExt(io.in.bits.uop.ctrl.imm(11,0), VAddrBits)
  val imm12 = WireInit(io.in.bits.uop.ctrl.imm(11,0))
  val saddr_lo = io.in.bits.src(0)(11,0) + Cat(0.U(1.W), imm12)
  val saddr_hi = Mux(saddr_lo(12),
    Mux(imm12(11), io.in.bits.src(0)(VAddrBits-1, 12), io.in.bits.src(0)(VAddrBits-1, 12)+1.U),
    Mux(imm12(11), io.in.bits.src(0)(VAddrBits-1, 12)+SignExt(1.U, VAddrBits-12), io.in.bits.src(0)(VAddrBits-1, 12)),
  )
  val saddr = Cat(saddr_hi, saddr_lo(11,0))

  // exception check
  val addrAligned = LookupTree(io.in.bits.uop.ctrl.fuOpType(1,0), List(
    "b00".U   -> true.B,              //b
    "b01".U   -> (io.out.bits.vaddr(0) === 0.U),   //h
    "b10".U   -> (io.out.bits.vaddr(1,0) === 0.U), //w
    "b11".U   -> (io.out.bits.vaddr(2,0) === 0.U)  //d
  ))

  io.lsqIn.bits := DontCare
  io.lsqIn.bits.vaddr := saddr
  io.lsqIn.bits.data := io.in.bits.src(1) // FIXME: remove data from pipeline
  io.lsqIn.bits.uop := io.in.bits.uop
  io.lsqIn.bits.miss := DontCare
  io.lsqIn.bits.rsIdx := io.rsIdx
  io.lsqIn.bits.mask := genWmask(io.out.bits.vaddr, io.in.bits.uop.ctrl.fuOpType(1,0))
  io.lsqIn.bits.isFirstIssue := io.isFirstIssue
  io.lsqIn.bits.wlineflag := io.in.bits.uop.ctrl.fuOpType === LSUOpType.cbo_zero
  io.lsqIn.valid := io.in.valid
  io.lsqIn.bits.uop.cf.exceptionVec(storeAddrMisaligned) := !addrAligned
  
  io.in.ready := io.lsqIn.ready

  io.lsqOut <> io.out

  XSPerfAccumulate("in_valid", io.in.valid)
  XSPerfAccumulate("in_fire", io.in.fire)
  XSPerfAccumulate("in_fire_first_issue", io.in.fire && io.isFirstIssue)
  XSPerfAccumulate("addr_spec_success", io.out.fire() && saddr(VAddrBits-1, 12) === io.in.bits.src(0)(VAddrBits-1, 12))
  XSPerfAccumulate("addr_spec_failed", io.out.fire() && saddr(VAddrBits-1, 12) =/= io.in.bits.src(0)(VAddrBits-1, 12))
  XSPerfAccumulate("addr_spec_success_once", io.out.fire() && saddr(VAddrBits-1, 12) === io.in.bits.src(0)(VAddrBits-1, 12) && io.isFirstIssue)
  XSPerfAccumulate("addr_spec_failed_once", io.out.fire() && saddr(VAddrBits-1, 12) =/= io.in.bits.src(0)(VAddrBits-1, 12) && io.isFirstIssue)
}

// Store Pipeline Stage 1
// TLB access 
class StoreUnit_S1(implicit p: Parameters) extends XSModule {
  val io = IO(new Bundle{
    val in = Flipped(DecoupledIO(new LsPipelineBundle))
    val out = DecoupledIO(new LsPipelineBundle)
    val dtlbReq = DecoupledIO(new TlbReq)
  })

  io.dtlbReq.bits.vaddr := io.in.bits.vaddr
  io.dtlbReq.valid := io.in.valid
  io.dtlbReq.bits.cmd := TlbCmd.write
  io.dtlbReq.bits.size := LSUOpType.size(io.in.bits.uop.ctrl.fuOpType)
  io.dtlbReq.bits.robIdx := io.in.bits.uop.robIdx
  io.dtlbReq.bits.debug.pc := io.in.bits.uop.cf.pc
  io.dtlbReq.bits.debug.isFirstIssue := io.in.bits.isFirstIssue

  io.in <> io.out

}

// Store Pipeline Stage 2
// TLB resp (send paddr to dcache)
class StoreUnit_S2(implicit p: Parameters) extends XSModule {
  val io = IO(new Bundle() {
    val in = Flipped(Decoupled(new LsPipelineBundle))
    val out = Decoupled(new LsPipelineBundle)
    val lsq = ValidIO(new LsPipelineBundle())
    val dtlbResp = Flipped(DecoupledIO(new TlbResp))
    val rsFeedback = ValidIO(new RSFeedback)
  })

  // mmio cbo decoder
  val is_mmio_cbo = io.in.bits.uop.ctrl.fuOpType === LSUOpType.cbo_clean ||
    io.in.bits.uop.ctrl.fuOpType === LSUOpType.cbo_flush ||
    io.in.bits.uop.ctrl.fuOpType === LSUOpType.cbo_inval

  val s2_paddr = io.dtlbResp.bits.paddr
  val s2_tlb_miss = io.dtlbResp.bits.miss
  val s2_mmio = is_mmio_cbo
  val s2_exception = selectStore(io.out.bits.uop.cf.exceptionVec, false).asUInt.orR

  io.in.ready := true.B

  io.dtlbResp.ready := true.B // TODO: why dtlbResp needs a ready?

  // Send TLB feedback to store issue queue
  // if store retry comes , do not feedback to rs to avoid assert-failing
  io.rsFeedback.valid := Mux(io.in.bits.isStoreRetry,false.B,io.in.valid)
  // io.rsFeedback.bits.hit := !s2_tlb_miss
  // do not let rs re-issue
  io.rsFeedback.bits.hit := true.B
  io.rsFeedback.bits.flushState := DontCare
  io.rsFeedback.bits.rsIdx := io.in.bits.rsIdx
  io.rsFeedback.bits.sourceType := DontCare
  XSDebug(io.rsFeedback.valid,
    "S1 Store: tlbHit: %d robIdx: %d\n",
    io.rsFeedback.bits.hit,
    io.rsFeedback.bits.rsIdx
  )
  io.rsFeedback.bits.dataInvalidSqIdx := DontCare

  // get paddr from dtlb, check if rollback is needed
  // writeback store inst to lsq
  io.out.valid := io.in.valid && !s2_tlb_miss
  io.out.bits := io.in.bits
  io.out.bits.paddr := s2_paddr
  io.out.bits.miss := false.B
  io.out.bits.mmio := s2_mmio
  io.out.bits.uop.cf.exceptionVec(storePageFault) := io.dtlbResp.bits.excp.pf.st
  io.out.bits.uop.cf.exceptionVec(storeAccessFault) := io.dtlbResp.bits.excp.af.st

  io.lsq.valid := io.in.valid
  io.lsq.bits := io.out.bits
  io.lsq.bits.miss := s2_tlb_miss

  // mmio inst with exception will be writebacked immediately
  // io.out.valid := io.in.valid && (!io.out.bits.mmio || s2_exception) && !s2_tlb_miss

  XSPerfAccumulate("in_valid", io.in.valid)
  XSPerfAccumulate("in_fire", io.in.fire)
  XSPerfAccumulate("in_fire_first_issue", io.in.fire && io.in.bits.isFirstIssue)
  XSPerfAccumulate("tlb_miss", io.in.fire && s2_tlb_miss)
  XSPerfAccumulate("tlb_miss_first_issue", io.in.fire && s2_tlb_miss && io.in.bits.isFirstIssue)
}

class StoreUnit_S3(implicit p: Parameters) extends XSModule {
  val io = IO(new Bundle() {
    val in = Flipped(Decoupled(new LsPipelineBundle))
    val pmpResp = Flipped(new PMPRespBundle)
    val out = Decoupled(new LsPipelineBundle)
  })

  val s3_exception = selectStore(io.out.bits.uop.cf.exceptionVec, false).asUInt.orR

  io.in.ready := true.B
  io.out.bits := io.in.bits
  io.out.bits.mmio := (io.in.bits.mmio || io.pmpResp.mmio) && !s3_exception
  io.out.bits.uop.cf.exceptionVec(storeAccessFault) := io.in.bits.uop.cf.exceptionVec(storeAccessFault) || io.pmpResp.st
  io.out.valid := io.in.valid && (!io.out.bits.mmio || s3_exception)
}

class StoreUnit_S4(implicit p: Parameters) extends XSModule {
  val io = IO(new Bundle() {
    val in = Flipped(Decoupled(new LsPipelineBundle))
    val stout = DecoupledIO(new ExuOutput) // writeback store
  })

  io.in.ready := true.B

  io.stout.valid := io.in.valid
  io.stout.bits.uop := io.in.bits.uop
  io.stout.bits.data := DontCare
  io.stout.bits.redirectValid := false.B
  io.stout.bits.redirect := DontCare
  io.stout.bits.debug.isMMIO := io.in.bits.mmio
  io.stout.bits.debug.paddr := DontCare
  io.stout.bits.debug.isPerfCnt := false.B
  io.stout.bits.fflags := DontCare

}

class StoreUnit(implicit p: Parameters) extends XSModule {
  val io = IO(new Bundle() {
    val stin = Flipped(Decoupled(new ExuInput))
    val redirect = Flipped(ValidIO(new Redirect))
    val feedbackSlow = ValidIO(new RSFeedback)
    val tlb = new TlbRequestIO()
    val pmp = Flipped(new PMPRespBundle())
    val rsIdx = Input(UInt(log2Up(IssQueSize).W))
    val isFirstIssue = Input(Bool())
    val lsq = ValidIO(new LsPipelineBundle)
    val lsq_replenish = Output(new LsPipelineBundle())
    val stout = DecoupledIO(new ExuOutput) // writeback store
    val lsqIn = Decoupled(new LsPipelineBundle)
    val lsqOut = Flipped(Decoupled(new LsPipelineBundle))
  })

  val store_s0 = Module(new StoreUnit_S0)
  val store_s1 = Module(new StoreUnit_S1)
  val store_s2 = Module(new StoreUnit_S2)
  val store_s3 = Module(new StoreUnit_S3)
  val store_s4 = Module(new StoreUnit_S4)

  store_s0.io.in <> io.stin
  store_s0.io.rsIdx := io.rsIdx
  store_s0.io.isFirstIssue := io.isFirstIssue
  io.lsqIn <> store_s0.io.lsqIn
  io.lsqOut <> store_s0.io.lsqOut

  PipelineConnect(store_s0.io.out, store_s1.io.in, true.B, store_s0.io.out.bits.uop.robIdx.needFlush(io.redirect))

  store_s1.io.dtlbReq <> io.tlb.req
  PipelineConnect(store_s1.io.out, store_s2.io.in, true.B, store_s1.io.out.bits.uop.robIdx.needFlush(io.redirect))


  store_s2.io.dtlbResp <> io.tlb.resp
  store_s2.io.rsFeedback <> io.feedbackSlow
  io.lsq <> store_s2.io.lsq

  PipelineConnect(store_s2.io.out, store_s3.io.in, true.B, store_s2.io.out.bits.uop.robIdx.needFlush(io.redirect))

  store_s3.io.pmpResp <> io.pmp
  io.lsq_replenish := store_s3.io.out.bits // mmio and exception
  PipelineConnect(store_s3.io.out, store_s4.io.in, true.B, store_s3.io.out.bits.uop.robIdx.needFlush(io.redirect))

  store_s4.io.stout <> io.stout

  private def printPipeLine(pipeline: LsPipelineBundle, cond: Bool, name: String): Unit = {
    XSDebug(cond,
      p"$name" + p" pc ${Hexadecimal(pipeline.uop.cf.pc)} " +
        p"addr ${Hexadecimal(pipeline.vaddr)} -> ${Hexadecimal(pipeline.paddr)} " +
        p"op ${Binary(pipeline.uop.ctrl.fuOpType)} " +
        p"data ${Hexadecimal(pipeline.data)} " +
        p"mask ${Hexadecimal(pipeline.mask)}\n"
    )
  }

  printPipeLine(store_s0.io.out.bits, store_s0.io.out.valid, "S0")
  printPipeLine(store_s2.io.out.bits, store_s2.io.out.valid, "S1")
}
