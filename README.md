# xdcr simulator

possibly live link: <http://s3.crate.im/xdsim/index.html> (refresh if things are wacky)

## build

get [lein](http://github.com/technomancy/leiningen)

    lein cljsbuild once xdsim

open index.html

### things to try

#### failover puts source behind target

 1. Add some items and do some updates to source
 1. Replicate source master to replica
 1. Do some more updates on source
 1. XDCR Source to Target
 1. Simulate a failover (replace master w/ replica)
 1. Update an item on the source that was updated in step #3
 1. XDCR Source to Target
 1. XDCR Target to Source

After the failover some items on Source will have lower Rev #s than items in Target, even though no writes have been done at Target. Writes to items in this state will be *ignored* by the target cluster until their Rev #s reach their old values.

In this case, updates done *after* the failover, even though no writes have been done to the target at *any* point, can be lost.

#### tombstone purging resets max-deleted-rev for fresh replicas

 1. Add some items and do some updates to source
 1. XDCR Source to Target
 1. Delete some items with high rev #s
 1. XDCR Source to Target
 1. Purge tombstones on source
 1. Replicate source master to replica
 1. Observe that "New Item Rev" on replica does not match master
 1. Simulate a failover (Replace master w/ Replica)
 1. Create a new item on Source
 1. Create an item with the same name on Target

At this point the item that was created on Source and Target will have a higher rev # on target, meaning that Target will ignore XDCR updates to that item from Source until the Rev # for that item exceeds it.

#### try to figure out how you would build a simple hit-counter on this system

Notice that even with *no* failures, much of the Couchbase API no longer works as expected. A simple hit-counter application that uses INCR, which would work against standard Couchbase without XDCR, will lose hits if writes go to both clusters.

