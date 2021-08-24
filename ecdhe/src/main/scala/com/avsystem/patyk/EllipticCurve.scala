package com.avsystem.patyk

import com.avsystem.commons.misc.Bytes
import com.avsystem.patyk.Algo.EgcdRes

import scala.annotation.{nowarn, tailrec}

object Algo {
  case class EgcdRes(x: BigInt, y: BigInt, d: BigInt)

  /**
   * Extended Euclidean algorithm for two numbers `a` and `b`.
   * Returns integers `x`, `y` and `d` where `d` is the greatest common divisor of `a` and `b` and the
   * Bézout's identity is satisfied, i.e. `x*a + y*b == d`.
   */
  def egcd(a: BigInt, b: BigInt): EgcdRes = {
    require(a >= 0 && b >= 0)

    @tailrec def loop(x: BigInt, y: BigInt, z: BigInt, u: BigInt): EgcdRes = {
      val a0 = x * a + y * b
      val b0 = z * a + u * b
      if (b0 == 0) EgcdRes(x, y, a0)
      else {
        val d = a0 / b0
        loop(z, u, x - d * z, y - d * u)
      }
    }

    loop(1, 0, 0, 1)
  }
}

@nowarn("msg=The outer reference in this type test cannot be checked at run time")
class MultModulo(p: BigInt) {
  private def mod(v: BigInt): BigInt =
    if (v >= 0) v % p else (v % p + p) % p

  final case class Elem private(value: BigInt) {
    override def toString: String = value.toString

    def inverse: Elem = Algo.egcd(value, p) match {
      case EgcdRes(ei, _, d) if d == BigInt(1) => Elem(ei)
      case _ => throw new IllegalArgumentException(s"$value and $p are not coprimes")
    }

    def unary_- : Elem = Elem(-value)

    def +(other: Elem): Elem = Elem(value + other.value)
    def -(other: Elem): Elem = this + (-other)
    def *(other: Elem): Elem = Elem(value * other.value)
    def /(other: Elem): Elem = this * other.inverse

    def *(factor: BigInt): Elem = this * Elem(factor)

    def pow(exp: BigInt): Elem = {
      @tailrec def loop(i: Int, curPow: Elem, acc: Elem): Elem =
        if (i >= exp.bitLength) acc
        else loop(i + 1, curPow * curPow, if (exp.testBit(i)) acc * curPow else acc)
      loop(0, this, Elem(1))
    }

    def hex: String = Bytes(value.toByteArray).hex
  }
  object Elem {
    def apply(value: BigInt): Elem = new Elem(mod(value))
  }

  @nowarn("msg=The outer reference in this type test cannot be checked at run time")
  class EllipticCurve(a: Elem, b: Elem) {
    require(a.pow(3) * 4 + b.pow(2) * 37 != Elem(0))

    def contains(point: Point): Boolean = point match {
      case Point.Infinity => true
      case Point.Finite(x, y) => y * y == x * x * x + a * x + b
    }

    final val Infinity: Point = Point.Infinity

    sealed trait Point {
      def hex: String = this match {
        case Point.Infinity => "Inf"
        case Point.Finite(x, y) => s"(${x.hex}, ${y.hex})"
      }

      def unary_- : Point = this match {
        case Point.Infinity => Point.Infinity
        case Point.Finite(x, y) => Point(x, -y)
      }

      def +(other: Point): Point = (this, other) match {
        case (Point.Infinity, other) => other
        case (thiz, Point.Infinity) => thiz
        case (Point.Finite(tx, ty), Point.Finite(ox, oy)) =>
          if (tx == ox && ty == -oy) Point.Infinity
          else {
            val s =
              if (tx == ox) (tx * tx * 3 + a) / (ty * 2)
              else (ty - oy) / (tx - ox)
            val sx = s * s - tx - ox
            val sy = ty + s * (sx - tx)
            Point(sx, -sy)
          }
      }

      def *(factor: BigInt): Point = {
        @tailrec def loop(i: Int, pow2: Point, acc: Point): Point =
          if (i >= factor.bitLength) acc
          else loop(i + 1, pow2 + pow2, if (factor.testBit(i)) acc + pow2 else acc)
        loop(0, this, Point.Infinity)
      }
    }
    object Point {
      case object Infinity extends Point
      case class Finite(x: Elem, y: Elem) extends Point {
        override def toString = s"($x, $y)"
      }

      def apply(x: Elem, y: Elem): Point = Finite(x, y)
      def apply(x: BigInt, y: BigInt): Point = Finite(Elem(x), Elem(y))
    }
  }
}
