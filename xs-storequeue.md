### 基本流程

这里写一下xs的storequeue的业务逻辑

+ sq是一个循环队列
+ 有三个重要的指针
  + enqptr：接受派遣阶段的store指令，接一条往后移动一下
  + deqptr：当所指向的这条store指令数据和地址准备好了，且被rob提交了，就可以从这里把它写到sbuffer里头去了，写一条往后移动一下，每一个周期最多往sbuffer里面写2条store
  + cmtptr：当所指向的这条store指令地址和数据都准备好了，可以被rob提交了，当rob提交时，它把提交的几条store设置为commited，然后向前移动
+ sq从dispatch阶段接受store指令
+ sq监听store流水线，一旦rs发了请求到store流水线上来，就要把对应的内容写到sq里面，设置状态等等
  + 地址：rs发给store流水线的请求首先给TLB进行转换，如果转换成功就把虚实地址，mask写到sq中，并设置addrvalid；如果转换失败就不写，等rs重发。具体这个请求是不是mmio，要等到下一个周期从pmp和pma中返回结果才知道
  + 数据：rs发给store流水线的store请求如果带data，就写到sq里面，并且**在这里直接拉一条线通知rob：store的数据已经写**。
  + rs是否会对一条store发数据和地址异步进行？
+ sq要提供“被查询”的机制，让load可以查找sq，找在它前面可以forward的
+ 当mmio的store变成了rob的最顶部，用一个状态机来控制这个流程
  + 当deq的那一条指令是mmio的，并且all is ready，且rob的顶部也是它了，就可以让它去mmio通道了
  + 给mmio通道发请求
  + 等待mmio通道返回
  + 给rob发完成信号
  + 等rob提交这条指令状态机重新回到idle状态
+ rob提交store指令后，把cmtptr对应的那些store设置为commited，这样deqptr就能看到，并且开始工作把他们写到sbuffer里面去了。
+ 如果因为发生异常或者分支预测失败需要取消，则是取消enqptr那边的store，按照失效的数量去取消
+ (store流水线只接受带地址的store，对于带数据的store，rs发到stdExeUnit，sq将其截取写到datamodule里面)



### 变动

如果要把选择重发改到lsq里面，需要哪些改动

+ 原本sq直接从store流水线接受TLB命中与否的请求，相当于rs发出的store请求先经过TLB，然后再来到sq。现在需要让sq也能给TLB发请求，而且rs发出的store请求先不经过TLB，而是先生成虚拟地址，然后发给sq，sq会拿到rs发来的请求，也会有自身之前TLB没有命中的请求，进行一个仲裁，发给TLB，根据TLB的结果设置对应的地址和状态。
+ rs发过来的数据则直接存下来即可

