package com.avsystem.patyk

import com.avsystem.commons.misc.{AbstractValueEnum, AbstractValueEnumCompanion, EnumCtx}

final class MessageType(implicit enumCtx: EnumCtx) extends AbstractValueEnum
object MessageType extends AbstractValueEnumCompanion[MessageType] {
  final val Request, Response, Error: Value = new MessageType
}
