### 概述
这里写一下xs的store流水线结构

storeunit接收rs发来的store请求，做的是地址部分的流水线

+ S0：生成访存的虚地址，给到TLB，检查miss align
+ S1：TLB出结果，命中与否，把这个结果反馈到rs，如果命中则不需要rs再重发，否则rs承担重发的任务，并把这个结果也给到sq，让sq对应的store指令的地址valid，从TLB里面得到异常信息写到uop中
+ S2：PMA得到mmio信息，PMP得到access fault信息，写到uop里面去，然后反馈给sq
+ S3：把uop里面的一些信息清除掉


### 可能的改动

或许可以加一个流水级，S0仍然做AGU，但是先不给到TLB，而是给到sq，然后sq进行仲裁，sq选出rs的或者是选择重发的的请求发出来，这个请求给到TLB，把这个作为S1，S2 TLB出结果，反馈到rs时欺骗一下rs即都命中(只有rs发出来的请求才去欺骗)，可以从rs中退出，重发的任务给到sq，把TLB命中等信息反馈到sq。S3 和之前的S2一样，S4和之前的S3一样

sq要能选择出 StorePipelineWidth 个重发的store，和 rs 可能发的 StorePipelineWidth 个store中选择 StorePipelineWidth 个放到store流水线上去。

rs一条store只会发一次，所以永远是rs发的store优先级高，一旦这条store TLB MISS就由sq来重发，sq重发的store不能一直在sq中发出请求，一旦选择了它，它就需要过一些周期才能再次发出请求，之后TLB会反馈这个信息，如果TLB命中则不需要重发了，如果TLB miss需要以一定策略等待一些周期再重发。

所以基本上只需要加上一个流水级让sq进行仲裁，让sq有仲裁的能力，让sq有每一个store地址转换成功与否等信息让它能选择性重发即可。