# zdalnie-sterowany-patyk

Educational demo of a Scala RPC library with non-blocking network layer, featuring:

* arbitrary traits interpreted as RPC interfaces (macro engine)
* `GenCodec` + CBOR for serialization
* Monix `Task` for asynchronicity
* TCP based communication
* non-blocking, low-level implementation using Java NIO
