package com.codahale.cassie.codecs.tests

import com.codahale.cassie.codecs.tests.ByteBufferLiteral._
import org.scalatest.Spec
import org.scalatest.matchers.MustMatchers
import com.codahale.cassie.codecs.FixedLongCodec
import com.codahale.cassie.types.FixedLong

class FixedLongCodecTest extends Spec with MustMatchers {
  describe("encoding a long") {
    it("produces a variable length zig-zag encoded array of bytes") {
      FixedLongCodec.encode(FixedLong(199181989101092820L)) must equal(bb(2, -61, -94, -10, -70, 6, -65, -44))
    }
  }

  describe("decoding an array of bytes") {
    it("produces a long") {
      FixedLongCodec.decode(bb(2, -61, -94, -10, -70, 6, -65, -44)) must equal(FixedLong(199181989101092820L))
    }
  }
}
