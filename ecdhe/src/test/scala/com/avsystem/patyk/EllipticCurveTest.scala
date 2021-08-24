package com.avsystem.patyk

import com.avsystem.commons.misc.Bytes
import org.scalatest.funsuite.AnyFunSuite

import java.security.SecureRandom

class EllipticCurveTest extends AnyFunSuite {
  // domain parameters for named elliptic curve `secp256r1`
  // https://www.secg.org/SEC2-Ver-1.0.pdf
  final val P = BigInt("FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF", 16)
  final val M = new MultModulo(P)

  final val A = M.Elem(BigInt("FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFC", 16))
  final val B = M.Elem(BigInt("5AC635D8AA3A93E7B3EBBD55769886BC651D06B0CC53B0F63BCE3C3E27D2604B", 16))
  final val C = new M.EllipticCurve(A, B)

  final val Gx = M.Elem(BigInt("6B17D1F2E12C4247F8BCE6E563A440F277037D812DEB33A0F4A13945D898C296", 16))
  final val Gy = M.Elem(BigInt("4FE342E2FE1A7F9B8EE7EB4A7C0F9E162BCE33576B315ECECBB6406837BF51F5", 16))
  final val G = C.Point(Gx, Gy)

  final val N = BigInt("FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551", 16)

  private val random = new SecureRandom

  private def randomSecret(): BigInt = {
    val bytes = new Array[Byte](32)
    random.nextBytes(bytes)
    BigInt(bytes) % N
  }

  test("diffie hellman") {
    // private
    val a = randomSecret()
    println(s"top secret of A: ${Bytes(a.toByteArray).hex}")
    val b = randomSecret()
    println(s"top secret of B: ${Bytes(b.toByteArray).hex}")

    // public
    val pA = G * a
    println(s"sending to B: ${pA.hex}")
    val pB = G * b
    println(s"sending to A: ${pB.hex}")

    val pBA = pB * a
    val pAB = pA * b

    println(s"shared secret in A: ${pBA.hex}")
    println(s"shared secret in B: ${pAB.hex}")

    assert(pBA == pAB)

    val sharedSecret: Array[Byte] = pBA match {
      case C.Point.Finite(x, _) => x.value.toByteArray
      case C.Point.Infinity => sys.error("impossibru")
    }

    println(s"final shared key: ${Bytes(sharedSecret).hex}")
  }
}
