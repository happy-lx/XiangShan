### 想法
loadqueue的复杂度比storequeue要高，但也有相通的地方。

storequeue的storeunit只需要rs重发那些tlb没有命中的store，但是loadqueue的loadunit需要rs重发：
+ tlb没有命中的load
+ dcache bank冲突的load
+ ld-ld 违例检查争用失败的load
+ dcache 需要replay的load
+ dcache mshr满的时候的load(dcache的mshr满了就不能处理load了)
+ load从store forward数据时store的数据还没有准备好
+ 注意：dcache miss的load是不需要rs重发的

对于load来说其实相比于store，除了都有的tlb miss的重发，只有几种额外情况：
+ load要的数据在dcache里面，但是dcache miss了，rs不重发这种情况，而是等dcache的mshr拿到数据之后refill到loadqueue里面去，所以重发不需要考虑这种情况

boom的loadqueue和storequeue重发只有一个区别，就是load多了一个wakeup，boom把上面的情况和forward fail的情况归类为需要wakeup的load，去重发他们，但是香山里面其实不需要考虑上面这个情况

这样看其实loadqueue的改法和storequeue基本上是一致的了。


这个software prefetch指令会进入load queue吗，这种指令还是让rs来重发吧


### TODO
+ 对loadqueue里面的信号进行更新
+ 软件的prefetch暂时先不管