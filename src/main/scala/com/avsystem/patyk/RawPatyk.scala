package com.avsystem.patyk

import com.avsystem.commons._
import com.avsystem.commons.meta.{MacroInstances, composite, multi}
import com.avsystem.commons.rpc.{AsRawReal, RawRpcCompanion, encoded, methodName}
import com.avsystem.commons.serialization.GenCodec
import com.avsystem.commons.serialization.cbor.{CborOutput, FieldLabels, RawCbor}
import monix.eval.Task

import scala.util.control.NoStackTrace

trait RawPatyk {
  @multi @encoded
  def invoke(@composite invocation: RawPatyk.Invocation): Task[RawCbor]
}
object RawPatyk extends RawRpcCompanion[RawPatyk] {
  case class Invocation(
    @methodName name: String,
    @encoded @multi args: IArraySeq[RawCbor]
  )

  final val InvocationCborLabels = new FieldLabels {
    def label(field: String): Opt[Int] = field match {
      case "name" => Opt(0)
      case "args" => Opt(1)
      case _ => Opt.Empty
    }

    def field(label: Int): Opt[String] = label match {
      case 0 => Opt("name")
      case 1 => Opt("args")
      case _ => Opt.Empty
    }
  }

  implicit val invocationCodec: GenCodec[Invocation] = GenCodec.materialize
}

case class PatykException(message: String, cause: Throwable = null)
  extends Exception(message, cause) with NoStackTrace

trait PatykImplicits {
  implicit def asRawCbor[T: GenCodec]: AsRawReal[RawCbor, T] =
    AsRawReal.create(
      t => RawCbor(CborOutput.write(t)),
      c => GenCodec.read[T](c.createInput(FieldLabels.NoLabels))
    )

  implicit def asRawCborTask[T: GenCodec]: AsRawReal[Task[RawCbor], Task[T]] =
    AsRawReal.create(_.map(asRawCbor[T].asRaw), _.map(asRawCbor[T].asReal))
}
object PatykImplicits extends PatykImplicits

abstract class PatykCompanion[T](implicit
  instances: MacroInstances[PatykImplicits, () => RawPatyk.AsRawRealRpc[T]]
) {
  implicit lazy val asRawPatyk: AsRawReal[RawPatyk, T] = instances(PatykImplicits, this).apply()
}
