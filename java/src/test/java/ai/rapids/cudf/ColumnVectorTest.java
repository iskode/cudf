/*
 *
 *  Copyright (c) 2019-2021, NVIDIA CORPORATION.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package ai.rapids.cudf;

import ai.rapids.cudf.HostColumnVector.BasicType;
import ai.rapids.cudf.HostColumnVector.DataType;
import ai.rapids.cudf.HostColumnVector.ListType;
import ai.rapids.cudf.HostColumnVector.StructData;
import ai.rapids.cudf.HostColumnVector.StructType;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static ai.rapids.cudf.QuantileMethod.HIGHER;
import static ai.rapids.cudf.QuantileMethod.LINEAR;
import static ai.rapids.cudf.QuantileMethod.LOWER;
import static ai.rapids.cudf.QuantileMethod.MIDPOINT;
import static ai.rapids.cudf.QuantileMethod.NEAREST;
import static ai.rapids.cudf.TableTest.assertColumnsAreEqual;
import static ai.rapids.cudf.TableTest.assertStructColumnsAreEqual;
import static ai.rapids.cudf.TableTest.assertTablesAreEqual;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public class ColumnVectorTest extends CudfTestBase {

  public static final double PERCENTAGE = 0.0001;

  // IEEE 754 NaN values
  static final float POSITIVE_FLOAT_NAN_LOWER_RANGE = Float.intBitsToFloat(0x7f800001);
  static final float POSITIVE_FLOAT_NAN_UPPER_RANGE = Float.intBitsToFloat(0x7fffffff);
  static final float NEGATIVE_FLOAT_NAN_LOWER_RANGE = Float.intBitsToFloat(0xff800001);
  static final float NEGATIVE_FLOAT_NAN_UPPER_RANGE = Float.intBitsToFloat(0xffffffff);

  static final double POSITIVE_DOUBLE_NAN_LOWER_RANGE = Double.longBitsToDouble(0x7ff0000000000001L);
  static final double POSITIVE_DOUBLE_NAN_UPPER_RANGE = Double.longBitsToDouble(0x7fffffffffffffffL);
  static final double NEGATIVE_DOUBLE_NAN_LOWER_RANGE = Double.longBitsToDouble(0xfff0000000000001L);
  static final double NEGATIVE_DOUBLE_NAN_UPPER_RANGE = Double.longBitsToDouble(0xffffffffffffffffL);

  // c = a * a - a
  static String ptx = "***(" +
      "      .func _Z1fPii(" +
      "        .param .b64 _Z1fPii_param_0," +
      "        .param .b32 _Z1fPii_param_1" +
      "  )" +
      "  {" +
      "        .reg .b32       %r<4>;" +
      "        .reg .b64       %rd<3>;" +
      "    ld.param.u64    %rd1, [_Z1fPii_param_0];" +
      "    ld.param.u32    %r1, [_Z1fPii_param_1];" +
      "    cvta.to.global.u64      %rd2, %rd1;" +
      "    mul.lo.s32      %r2, %r1, %r1;" +
      "    sub.s32         %r3, %r2, %r1;" +
      "    st.global.u32   [%rd2], %r3;" +
      "    ret;" +
      "  }" +
      ")***";

  static String cuda = "__device__ inline void f(" +
      "int* output," +
      "int input" +
      "){" +
      "*output = input*input - input;" +
      "}";

  @Test
  void testTransformVector() {
    try (ColumnVector cv = ColumnVector.fromBoxedInts(2,3,null,4);
         ColumnVector cv1 = cv.transform(ptx, true);
         ColumnVector cv2 = cv.transform(cuda, false);
         ColumnVector expected = ColumnVector.fromBoxedInts(2*2-2, 3*3-3, null, 4*4-4)) {
      TableTest.assertColumnsAreEqual(expected, cv1);
      TableTest.assertColumnsAreEqual(expected, cv2);
    }
  }

  @Test
  void testClampDouble() {
    try (ColumnVector cv = ColumnVector.fromDoubles(2.33d, 32.12d, -121.32d, 0.0d, 0.00001d,
        Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, Double.NaN);
         Scalar num = Scalar.fromDouble(0);
         Scalar loReplace = Scalar.fromDouble(-1);
         Scalar hiReplace = Scalar.fromDouble(1);
         ColumnVector result = cv.clamp(num, loReplace, num, hiReplace);
         ColumnVector expected = ColumnVector.fromDoubles(1.0d, 1.0d, -1.0d, 0.0d, 1.0d, -1.0d,
             1.0d, Double.NaN)) {
      assertColumnsAreEqual(expected, result);
    }
  }

  @Test
  void testClampFloat() {
    try (ColumnVector cv = ColumnVector.fromBoxedFloats(2.33f, 32.12f, null, -121.32f, 0.0f, 0.00001f, Float.NEGATIVE_INFINITY,
        Float.POSITIVE_INFINITY, Float.NaN);
         Scalar num = Scalar.fromFloat(0);
         Scalar loReplace = Scalar.fromFloat(-1);
         Scalar hiReplace = Scalar.fromFloat(1);
         ColumnVector result = cv.clamp(num, loReplace, num, hiReplace);
         ColumnVector expected = ColumnVector.fromBoxedFloats(1.0f, 1.0f, null, -1.0f, 0.0f, 1.0f, -1.0f, 1.0f, Float.NaN)) {
     assertColumnsAreEqual(expected, result);
    }
  }

  @Test
  void testClampLong() {
    try (ColumnVector cv = ColumnVector.fromBoxedLongs(1l, 3l, 6l, -2l, 23l, -0l, -90l, null);
         Scalar num = Scalar.fromLong(0);
         Scalar loReplace = Scalar.fromLong(-1);
         Scalar hiReplace = Scalar.fromLong(1);
         ColumnVector result = cv.clamp(num, loReplace, num, hiReplace);
         ColumnVector expected = ColumnVector.fromBoxedLongs(1l, 1l, 1l, -1l, 1l, 0l, -1l, null)) {
      assertColumnsAreEqual(expected, result);
    }
  }

  @Test
  void testClampShort() {
    try (ColumnVector cv = ColumnVector.fromShorts(new short[]{1, 3, 6, -2, 23, -0, -90});
         Scalar lo = Scalar.fromShort((short)1);
         Scalar hi = Scalar.fromShort((short)2);
         ColumnVector result = cv.clamp(lo, hi);
         ColumnVector expected = ColumnVector.fromShorts(new short[]{1, 2, 2, 1, 2, 1, 1})) {
      assertColumnsAreEqual(expected, result);
    }
  }

  @Test
  void testClampInt() {
      try (ColumnVector cv = ColumnVector.fromInts(1, 3, 6, -2, 23, -0, -90);
           Scalar num = Scalar.fromInt(0);
           Scalar hiReplace = Scalar.fromInt(1);
           Scalar loReplace = Scalar.fromInt(-1);
           ColumnVector result = cv.clamp(num, loReplace, num, hiReplace);
           ColumnVector expected = ColumnVector.fromInts(1, 1, 1, -1, 1, 0, -1)) {
          assertColumnsAreEqual(expected, result);
      }
  }

  @Test
  void testGetElementInt() {
    try (ColumnVector cv = ColumnVector.fromBoxedInts(3, 2, 1, null);
         Scalar s0 = cv.getScalarElement(0);
         Scalar s1 = cv.getScalarElement(1);
         Scalar s2 = cv.getScalarElement(2);
         Scalar s3 = cv.getScalarElement(3)) {
      assertEquals(3, s0.getInt());
      assertEquals(2, s1.getInt());
      assertEquals(1, s2.getInt());
      assertFalse(s3.isValid());
    }
  }

  @Test
  void testGetElementByte() {
    try (ColumnVector cv = ColumnVector.fromBoxedBytes((byte)3, (byte)2, (byte)1, null);
         Scalar s0 = cv.getScalarElement(0);
         Scalar s1 = cv.getScalarElement(1);
         Scalar s2 = cv.getScalarElement(2);
         Scalar s3 = cv.getScalarElement(3)) {
      assertEquals(3, s0.getByte());
      assertEquals(2, s1.getByte());
      assertEquals(1, s2.getByte());
      assertFalse(s3.isValid());
    }
  }

  @Test
  void testGetElementFloat() {
    try (ColumnVector cv = ColumnVector.fromBoxedFloats(3f, 2f, 1f, null);
         Scalar s0 = cv.getScalarElement(0);
         Scalar s1 = cv.getScalarElement(1);
         Scalar s2 = cv.getScalarElement(2);
         Scalar s3 = cv.getScalarElement(3)) {
      assertEquals(3f, s0.getFloat());
      assertEquals(2f, s1.getFloat());
      assertEquals(1f, s2.getFloat());
      assertFalse(s3.isValid());
    }
  }

  @Test
  void testGetElementString() {
    try (ColumnVector cv = ColumnVector.fromStrings("3a", "2b", "1c", null);
         Scalar s0 = cv.getScalarElement(0);
         Scalar s1 = cv.getScalarElement(1);
         Scalar s2 = cv.getScalarElement(2);
         Scalar s3 = cv.getScalarElement(3)) {
      assertEquals("3a", s0.getJavaString());
      assertEquals("2b", s1.getJavaString());
      assertEquals("1c", s2.getJavaString());
      assertFalse(s3.isValid());
    }
  }

  @Test
  void testGetElementDecimal() {
    try (ColumnVector cv = ColumnVector.decimalFromLongs(1,3, 2, 1, -1);
         Scalar s0 = cv.getScalarElement(0);
         Scalar s1 = cv.getScalarElement(1);
         Scalar s2 = cv.getScalarElement(2);
         Scalar s3 = cv.getScalarElement(3)) {
      assertEquals(1, s0.getType().getScale());
      assertEquals(new BigDecimal("3E+1"), s0.getBigDecimal());
      assertEquals(new BigDecimal("2E+1"), s1.getBigDecimal());
      assertEquals(new BigDecimal("1E+1"), s2.getBigDecimal());
      assertEquals(new BigDecimal("-1E+1"), s3.getBigDecimal());
    }
  }

  @Test
  void testGetElementList() {
    HostColumnVector.DataType dt = new HostColumnVector.ListType(true,
        new HostColumnVector.BasicType(true, DType.INT32));
    try (ColumnVector cv = ColumnVector.fromLists(dt, Arrays.asList(3, 2),
        Arrays.asList(1), Arrays.asList(), null);
         Scalar s0 = cv.getScalarElement(0);
         ColumnView s0Cv = s0.getListAsColumnView();
         ColumnVector expected0 = ColumnVector.fromInts(3, 2);
         Scalar s1 = cv.getScalarElement(1);
         ColumnView s1Cv = s1.getListAsColumnView();
         ColumnVector expected1 = ColumnVector.fromInts(1);
         Scalar s2 = cv.getScalarElement(2);
         ColumnView s2Cv = s2.getListAsColumnView();
         ColumnVector expected2 = ColumnVector.fromInts();
         Scalar s3 = cv.getScalarElement(3)) {
      assertColumnsAreEqual(expected0, s0Cv);
      assertColumnsAreEqual(expected1, s1Cv);
      assertColumnsAreEqual(expected2, s2Cv);
      assertFalse(s3.isValid());
    }
  }

 @Test
  void testStringCreation() {
    try (ColumnVector cv = ColumnVector.fromStrings("d", "sd", "sde", null, "END");
         HostColumnVector host = cv.copyToHost();
         ColumnVector backAgain = host.copyToDevice()) {
      TableTest.assertColumnsAreEqual(cv, backAgain);
    }
  }

  @Test
  void testUTF8StringCreation() {
    try (ColumnVector cv = ColumnVector.fromUTF8Strings(
            "d".getBytes(StandardCharsets.UTF_8),
            "sd".getBytes(StandardCharsets.UTF_8),
            "sde".getBytes(StandardCharsets.UTF_8),
            null,
            "END".getBytes(StandardCharsets.UTF_8));
         ColumnVector expected = ColumnVector.fromStrings("d", "sd", "sde", null, "END")) {
      TableTest.assertColumnsAreEqual(expected, cv);
    }
  }

  @Test
  void testRefCountLeak() throws InterruptedException {
    assumeTrue(Boolean.getBoolean("ai.rapids.cudf.flaky-tests-enabled"));
    long expectedLeakCount = MemoryCleaner.leakCount.get() + 1;
    ColumnVector.fromInts(1, 2, 3);
    long maxTime = System.currentTimeMillis() + 10_000;
    long leakNow;
    do {
      System.gc();
      Thread.sleep(50);
      leakNow = MemoryCleaner.leakCount.get();
    } while (leakNow != expectedLeakCount && System.currentTimeMillis() < maxTime);
    assertEquals(expectedLeakCount, MemoryCleaner.leakCount.get());
  }

  @Test
  void testConcatTypeError() {
    try (ColumnVector v0 = ColumnVector.fromInts(1, 2, 3, 4);
         ColumnVector v1 = ColumnVector.fromFloats(5.0f, 6.0f)) {
      assertThrows(CudfException.class, () -> ColumnVector.concatenate(v0, v1));
    }
  }

  @Test
  void testConcatNoNulls() {
    try (ColumnVector v0 = ColumnVector.fromInts(1, 2, 3, 4);
         ColumnVector v1 = ColumnVector.fromInts(5, 6, 7);
         ColumnVector v2 = ColumnVector.fromInts(8, 9);
         ColumnVector v = ColumnVector.concatenate(v0, v1, v2);
         ColumnVector expected = ColumnVector.fromInts(1, 2, 3, 4, 5, 6, 7, 8, 9)) {
      TableTest.assertColumnsAreEqual(expected, v);
    }
  }

  @Test
  void testConcatWithNulls() {
    try (ColumnVector v0 = ColumnVector.fromDoubles(1, 2, 3, 4);
         ColumnVector v1 = ColumnVector.fromDoubles(5, 6, 7);
         ColumnVector v2 = ColumnVector.fromBoxedDoubles(null, 9.0);
         ColumnVector v = ColumnVector.concatenate(v0, v1, v2);
         ColumnVector expected = ColumnVector.fromBoxedDoubles(1., 2., 3., 4., 5., 6., 7., null, 9.)) {
      TableTest.assertColumnsAreEqual(expected, v);
    }
  }

  @Test
  void testConcatStrings() {
    try (ColumnVector v0 = ColumnVector.fromStrings("0","1","2",null);
         ColumnVector v1 = ColumnVector.fromStrings(null, "5", "6","7");
         ColumnVector expected = ColumnVector.fromStrings(
           "0","1","2",null,
           null,"5","6","7");
         ColumnVector v = ColumnVector.concatenate(v0, v1)) {
      assertColumnsAreEqual(v, expected);
    }
  }

  @Test
  void testConcatTimestamps() {
    try (ColumnVector v0 = ColumnVector.timestampMicroSecondsFromBoxedLongs(0L, 1L, 2L, null);
         ColumnVector v1 = ColumnVector.timestampMicroSecondsFromBoxedLongs(null, 5L, 6L, 7L);
         ColumnVector expected = ColumnVector.timestampMicroSecondsFromBoxedLongs(
           0L, 1L, 2L, null,
           null, 5L, 6L, 7L);
         ColumnVector v = ColumnVector.concatenate(v0, v1)) {
      assertColumnsAreEqual(v, expected);
    }
  }

  @Test
  void testNormalizeNANsAndZeros() {
    // Must check boundaries of NaN representation, as described in javadoc for Double#longBitsToDouble.
    // @see java.lang.Double#longBitsToDouble
    // <quote>
    //      If the argument is any value in the range 0x7ff0000000000001L through 0x7fffffffffffffffL,
    //      or in the range 0xfff0000000000001L through 0xffffffffffffffffL, the result is a NaN.
    // </quote>
    final double MIN_PLUS_NaN  = Double.longBitsToDouble(0x7ff0000000000001L);
    final double MAX_PLUS_NaN  = Double.longBitsToDouble(0x7fffffffffffffffL);
    final double MAX_MINUS_NaN = Double.longBitsToDouble(0xfff0000000000001L);
    final double MIN_MINUS_NaN = Double.longBitsToDouble(0xffffffffffffffffL);

    Double[]  ins = new Double[] {0.0, -0.0, Double.NaN, MIN_PLUS_NaN, MAX_PLUS_NaN, MIN_MINUS_NaN, MAX_MINUS_NaN, null};
    Double[] outs = new Double[] {0.0,  0.0, Double.NaN,   Double.NaN,   Double.NaN,    Double.NaN,    Double.NaN, null};

    try (ColumnVector input = ColumnVector.fromBoxedDoubles(ins);
         ColumnVector expectedColumn = ColumnVector.fromBoxedDoubles(outs);
         ColumnVector normalizedColumn = input.normalizeNANsAndZeros()) {
      try (HostColumnVector expected = expectedColumn.copyToHost();
           HostColumnVector normalized = normalizedColumn.copyToHost()) {
        for (int i = 0; i<input.getRowCount(); ++i) {
          if (expected.isNull(i)) {
            assertTrue(normalized.isNull(i));
          }
          else {
            assertEquals(
                    Double.doubleToRawLongBits(expected.getDouble(i)),
                    Double.doubleToRawLongBits(normalized.getDouble(i))
            );
          }
        }
      }
    }
  }

  @Test
  void testMD5HashStrings() {
    try (ColumnVector v0 = ColumnVector.fromStrings(
          "a", "B\n", "dE\"\u0100\t\u0101 \ud720\ud721",
          "A very long (greater than 128 bytes/char string) to test a multi hash-step data point " +
          "in the MD5 hash function. This string needed to be longer.",
          null, null);
         ColumnVector v1 = ColumnVector.fromStrings(
           null, "c", "\\Fg2\'",
           "A 60 character string to test MD5's message padding algorithm",
           "hiJ\ud720\ud721\ud720\ud721", null);
         ColumnVector v2 = ColumnVector.fromStrings(
           "a", "B\nc",  "dE\"\u0100\t\u0101 \ud720\ud721\\Fg2\'",
           "A very long (greater than 128 bytes/char string) to test a multi hash-step data point " +
           "in the MD5 hash function. This string needed to be longer.A 60 character string to " +
           "test MD5's message padding algorithm",
           "hiJ\ud720\ud721\ud720\ud721", null);
         ColumnVector result01 = ColumnVector.md5Hash(v0, v1);
         ColumnVector result2 = ColumnVector.md5Hash(v2);
         ColumnVector expected = ColumnVector.fromStrings(
          "0cc175b9c0f1b6a831c399e269772661", "f5112705c2f6dc7d3fc6bd496df6c2e8",
          "d49db62680847e0e7107e0937d29668e", "8fa29148f63c1fe9248fdc4644e3a193",
          "1bc221b25e6c4825929e884092f4044f", "d41d8cd98f00b204e9800998ecf8427e")) {
      assertColumnsAreEqual(result01, expected);
      assertColumnsAreEqual(result2, expected);
    }
  }

  @Test
  void testMD5HashInts() {
    try (ColumnVector v0 = ColumnVector.fromBoxedInts(0, 100, null, null, Integer.MIN_VALUE, null);
         ColumnVector v1 = ColumnVector.fromBoxedInts(0, null, -100, null, null, Integer.MAX_VALUE);
         ColumnVector result = ColumnVector.md5Hash(v0, v1);
         ColumnVector expected = ColumnVector.fromStrings(
          "7dea362b3fac8e00956a4952a3d4f474", "cdc824bf721df654130ed7447fb878ac",
          "7fb89558e395330c6a10ab98915fcafb", "d41d8cd98f00b204e9800998ecf8427e",
          "152afd7bf4dbe26f85032eee0269201a", "ed0622e1179e101cf7edc0b952cc5262")) {
      assertColumnsAreEqual(expected, result);
    }
  }

  @Test
  void testMD5HashDoubles() {
    try (ColumnVector v = ColumnVector.fromBoxedDoubles(
          0.0, null, 100.0, -100.0, Double.MIN_NORMAL, Double.MAX_VALUE,
          POSITIVE_DOUBLE_NAN_UPPER_RANGE, POSITIVE_DOUBLE_NAN_LOWER_RANGE,
          NEGATIVE_DOUBLE_NAN_UPPER_RANGE, NEGATIVE_DOUBLE_NAN_LOWER_RANGE,
          Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY);
         ColumnVector result = ColumnVector.md5Hash(v);
         ColumnVector expected = ColumnVector.fromStrings(
          "7dea362b3fac8e00956a4952a3d4f474", "d41d8cd98f00b204e9800998ecf8427e",
          "6f5b4a57fd3aeb25cd33aa6c56512fd4", "b36ce1b64164e8f12c52ee5f1131ec01",
          "f7fbcdce3cf1bea8defd4ca29dabeb75", "d466cb643c6da6c31c88f4d482bccfd3",
          "bf26d90b64827fdbc58da0aa195156fe", "bf26d90b64827fdbc58da0aa195156fe",
          "bf26d90b64827fdbc58da0aa195156fe", "bf26d90b64827fdbc58da0aa195156fe",
          "73c82437c94e197f7e35e14f0140497a", "740660a5f71e7a264fca45330c34da31")) {
      assertColumnsAreEqual(expected, result);
    }
  }

  @Test
  void testMD5HashFloats() {
    try (ColumnVector v = ColumnVector.fromBoxedFloats(
          0f, 100f, -100f, Float.MIN_NORMAL, Float.MAX_VALUE, null,
          POSITIVE_FLOAT_NAN_LOWER_RANGE, POSITIVE_FLOAT_NAN_UPPER_RANGE,
          NEGATIVE_FLOAT_NAN_LOWER_RANGE, NEGATIVE_FLOAT_NAN_UPPER_RANGE,
          Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY);
         ColumnVector result = ColumnVector.md5Hash(v);
         ColumnVector expected = ColumnVector.fromStrings(
          "f1d3ff8443297732862df21dc4e57262", "a5d1e9463fae706307f90b05e9e6db9a",
          "556915a037c2ce1adfbedd7ca24794ea", "59331a73da50b419339c0d67a9ec1a97",
          "0ac9ada9698891bfc3f74bcee7e3f675", "d41d8cd98f00b204e9800998ecf8427e",
          "d6fd2bac25776d9a7269ca0e24b21b36", "d6fd2bac25776d9a7269ca0e24b21b36",
          "d6fd2bac25776d9a7269ca0e24b21b36", "d6fd2bac25776d9a7269ca0e24b21b36",
          "55e3a4db046ad9065bd7d64243de408f", "33b552ad34a825b275f5f2b59778b5c3")){
      assertColumnsAreEqual(expected, result);
    }
  }

  @Test
  void testMD5HashBools() {
    try (ColumnVector v0 = ColumnVector.fromBoxedBooleans(null, true, false, true, null, false);
         ColumnVector v1 = ColumnVector.fromBoxedBooleans(null, true, false, null, false, true);
         ColumnVector result = ColumnVector.md5Hash(v0, v1);
         ColumnVector expected = ColumnVector.fromStrings(
          "d41d8cd98f00b204e9800998ecf8427e", "249ba6277758050695e8f5909bacd6d3",
          "c4103f122d27677c9db144cae1394a66", "55a54008ad1ba589aa210d2629c1df41",
          "93b885adfe0da089cdf634904fd59f71", "441077cc9e57554dd476bdfb8b8b8102")) {
      assertColumnsAreEqual(expected, result);
    }
  }

  @Test
  void testMD5HashMixed() {
    try (ColumnVector strings = ColumnVector.fromStrings(
          "a", "B\n", "dE\"\u0100\t\u0101 \ud720\ud721",
          "A very long (greater than 128 bytes/char string) to test a multi hash-step data point " +
          "in the MD5 hash function. This string needed to be longer.",
          null, null);
         ColumnVector integers = ColumnVector.fromBoxedInts(0, 100, -100, Integer.MIN_VALUE, Integer.MAX_VALUE, null);
         ColumnVector doubles = ColumnVector.fromBoxedDoubles(
          0.0, 100.0, -100.0, POSITIVE_DOUBLE_NAN_LOWER_RANGE, POSITIVE_DOUBLE_NAN_UPPER_RANGE, null);
         ColumnVector floats = ColumnVector.fromBoxedFloats(
          0f, 100f, -100f, NEGATIVE_FLOAT_NAN_LOWER_RANGE, NEGATIVE_FLOAT_NAN_UPPER_RANGE, null);
         ColumnVector bools = ColumnVector.fromBoxedBooleans(true, false, null, false, true, null);
         ColumnVector result = ColumnVector.md5Hash(strings, integers, doubles, floats, bools);
         ColumnVector expected = ColumnVector.fromStrings(
          "c12c8638819fdd8377bbf537a4ebf0b4", "abad86357c1ae28eeb89f4b59700946a",
          "7e376255c6354716cd63418208dc7b90", "2f64d6a1d5b730fd97115924cf9aa486",
          "9f9d26bb5d25d56453a91f0558370fa4", "d41d8cd98f00b204e9800998ecf8427e")) {
      assertColumnsAreEqual(expected, result);
    }
  }

  @Test
  void testMD5HashLists() {
    List<String> list1 = Arrays.asList("dE\"\u0100\t\u0101 \u0500\u0501", "\\Fg2\'");
    List<String> list2 = Arrays.asList("A very long (greater than 128 bytes/char string) to test a multi hash-step data point " +
    "in the MD5 hash function. This string needed to be longer.", "", null, "A 60 character string to test MD5's message padding algorithm");
    List<String> list3 = Arrays.asList("hiJ\ud720\ud721\ud720\ud721");
    List<String> list4 = null;
    try (ColumnVector v = ColumnVector.fromLists(new HostColumnVector.ListType(true,
    new HostColumnVector.BasicType(true, DType.STRING)), list1, list2, list3, list4);
         ColumnVector result = ColumnVector.md5Hash(v);
         ColumnVector expected = ColumnVector.fromStrings(
          "675c30ce6d1b27dcb5009b01be42e9bd", "8fa29148f63c1fe9248fdc4644e3a193",
          "1bc221b25e6c4825929e884092f4044f", "d41d8cd98f00b204e9800998ecf8427e")) {
      assertColumnsAreEqual(expected, result);
    }
  }
  @Test
  void testSerial32BitMurmur3HashStrings() {
    try (ColumnVector v0 = ColumnVector.fromStrings(
           "a", "B\nc",  "dE\"\u0100\t\u0101 \ud720\ud721\\Fg2\'",
           "A very long (greater than 128 bytes/char string) to test a multi hash-step data point " +
           "in the MD5 hash function. This string needed to be longer.A 60 character string to " +
           "test MD5's message padding algorithm",
           "hiJ\ud720\ud721\ud720\ud721", null);
         ColumnVector result = ColumnVector.serial32BitMurmurHash3(42, new ColumnVector[]{v0});
         ColumnVector expected = ColumnVector.fromBoxedInts(-1293573533, 1163854319, 1423943036, 1504480835, 1249086584, 42)) {
      assertColumnsAreEqual(expected, result);
    }
  }

  @Test
  void testSerial32BitMurmur3HashInts() {
    try (ColumnVector v0 = ColumnVector.fromBoxedInts(0, 100, null, null, Integer.MIN_VALUE, null);
         ColumnVector v1 = ColumnVector.fromBoxedInts(0, null, -100, null, null, Integer.MAX_VALUE);
         ColumnVector result = ColumnVector.serial32BitMurmurHash3(42, new ColumnVector[]{v0, v1});
         ColumnVector expected = ColumnVector.fromBoxedInts(59727262, 751823303, -1080202046, 42, 723455942, 133916647)) {
      assertColumnsAreEqual(expected, result);
    }
  }

  @Test
  void testSerial32BitMurmur3HashDoubles() {
    try (ColumnVector v = ColumnVector.fromBoxedDoubles(
          0.0, null, 100.0, -100.0, Double.MIN_NORMAL, Double.MAX_VALUE,
          POSITIVE_DOUBLE_NAN_UPPER_RANGE, POSITIVE_DOUBLE_NAN_LOWER_RANGE,
          NEGATIVE_DOUBLE_NAN_UPPER_RANGE, NEGATIVE_DOUBLE_NAN_LOWER_RANGE,
          Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY);
         ColumnVector result = ColumnVector.serial32BitMurmurHash3(new ColumnVector[]{v});
         ColumnVector expected = ColumnVector.fromBoxedInts(1669671676, 0, -544903190, -1831674681, 150502665, 474144502, 1428788237, 1428788237, 1428788237, 1428788237, 420913893, 1915664072)) {
      assertColumnsAreEqual(expected, result);
    }
  }

  @Test
  void testSerialBitMurmur3HashFloats() {
    try (ColumnVector v = ColumnVector.fromBoxedFloats(
          0f, 100f, -100f, Float.MIN_NORMAL, Float.MAX_VALUE, null,
          POSITIVE_FLOAT_NAN_LOWER_RANGE, POSITIVE_FLOAT_NAN_UPPER_RANGE,
          NEGATIVE_FLOAT_NAN_LOWER_RANGE, NEGATIVE_FLOAT_NAN_UPPER_RANGE,
          Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY);
         ColumnVector result = ColumnVector.serial32BitMurmurHash3(411, new ColumnVector[]{v});
         ColumnVector expected = ColumnVector.fromBoxedInts(-235179434, 1812056886, 2028471189, 1775092689, -1531511762, 411, -1053523253, -1053523253, -1053523253, -1053523253, -1526256646, 930080402)){
      assertColumnsAreEqual(expected, result);
    }
  }

  @Test
  void testSerial32BitMurmur3HashBools() {
    try (ColumnVector v0 = ColumnVector.fromBoxedBooleans(null, true, false, true, null, false);
         ColumnVector v1 = ColumnVector.fromBoxedBooleans(null, true, false, null, false, true);
         ColumnVector result = ColumnVector.serial32BitMurmurHash3(0, new ColumnVector[]{v0, v1});
         ColumnVector expected = ColumnVector.fromBoxedInts(0, 884701402, 1032769583, -463810133, 1364076727, -991270669)) {
      assertColumnsAreEqual(expected, result);
    }
  }

  @Test
  void testSerial32BitMurmur3HashMixed() {
    try (ColumnVector strings = ColumnVector.fromStrings(
          "a", "B\n", "dE\"\u0100\t\u0101 \ud720\ud721",
          "A very long (greater than 128 bytes/char string) to test a multi hash-step data point " +
          "in the MD5 hash function. This string needed to be longer.",
          null, null);
         ColumnVector integers = ColumnVector.fromBoxedInts(0, 100, -100, Integer.MIN_VALUE, Integer.MAX_VALUE, null);
         ColumnVector doubles = ColumnVector.fromBoxedDoubles(
          0.0, 100.0, -100.0, POSITIVE_DOUBLE_NAN_LOWER_RANGE, POSITIVE_DOUBLE_NAN_UPPER_RANGE, null);
         ColumnVector floats = ColumnVector.fromBoxedFloats(
          0f, 100f, -100f, NEGATIVE_FLOAT_NAN_LOWER_RANGE, NEGATIVE_FLOAT_NAN_UPPER_RANGE, null);
         ColumnVector bools = ColumnVector.fromBoxedBooleans(true, false, null, false, true, null);
         ColumnVector result = ColumnVector.serial32BitMurmurHash3(1868, new ColumnVector[]{strings, integers, doubles, floats, bools});
         ColumnVector expected = ColumnVector.fromBoxedInts(387200465, 1988790727, 774895031, 814731646, -1073686048, 1868)) {
      assertColumnsAreEqual(expected, result);
    }
  }

  @Test
  void testSerial32BitMurmur3HashStruct() {
    try (ColumnVector strings = ColumnVector.fromStrings(
        "a", "B\n", "dE\"\u0100\t\u0101 \ud720\ud721",
        "A very long (greater than 128 bytes/char string) to test a multi hash-step data point " +
            "in the MD5 hash function. This string needed to be longer.",
        null, null);
         ColumnVector integers = ColumnVector.fromBoxedInts(0, 100, -100, Integer.MIN_VALUE, Integer.MAX_VALUE, null);
         ColumnVector doubles = ColumnVector.fromBoxedDoubles(
             0.0, 100.0, -100.0, POSITIVE_DOUBLE_NAN_LOWER_RANGE, POSITIVE_DOUBLE_NAN_UPPER_RANGE, null);
         ColumnVector floats = ColumnVector.fromBoxedFloats(
             0f, 100f, -100f, NEGATIVE_FLOAT_NAN_LOWER_RANGE, NEGATIVE_FLOAT_NAN_UPPER_RANGE, null);
         ColumnVector bools = ColumnVector.fromBoxedBooleans(true, false, null, false, true, null);
         ColumnVector result = ColumnVector.serial32BitMurmurHash3(1868, new ColumnVector[]{strings, integers, doubles, floats, bools});
         ColumnVector expected = ColumnVector.fromBoxedInts(387200465, 1988790727, 774895031, 814731646, -1073686048, 1868)) {
      assertColumnsAreEqual(expected, result);
    }
  }

  @Test
  void testSpark32BitMurmur3HashStrings() {
    try (ColumnVector v0 = ColumnVector.fromStrings(
           "a", "B\nc",  "dE\"\u0100\t\u0101 \ud720\ud721\\Fg2\'",
           "A very long (greater than 128 bytes/char string) to test a multi hash-step data point " +
           "in the MD5 hash function. This string needed to be longer.A 60 character string to " +
           "test MD5's message padding algorithm",
           "hiJ\ud720\ud721\ud720\ud721", null);
         ColumnVector result = ColumnVector.spark32BitMurmurHash3(42, new ColumnVector[]{v0});
         ColumnVector expected = ColumnVector.fromBoxedInts(1485273170, 1709559900, 1423943036, 176121990, 1199621434, 42)) {
      assertColumnsAreEqual(expected, result);
    }
  }

  @Test
  void testSpark32BitMurmur3HashInts() {
    try (ColumnVector v0 = ColumnVector.fromBoxedInts(0, 100, null, null, Integer.MIN_VALUE, null);
         ColumnVector v1 = ColumnVector.fromBoxedInts(0, null, -100, null, null, Integer.MAX_VALUE);
         ColumnVector result = ColumnVector.spark32BitMurmurHash3(42, new ColumnVector[]{v0, v1});
         ColumnVector expected = ColumnVector.fromBoxedInts(59727262, 751823303, -1080202046, 42, 723455942, 133916647)) {
      assertColumnsAreEqual(expected, result);
    }
  }

  @Test
  void testSpark32BitMurmur3HashDoubles() {
    try (ColumnVector v = ColumnVector.fromBoxedDoubles(
          0.0, null, 100.0, -100.0, Double.MIN_NORMAL, Double.MAX_VALUE,
          POSITIVE_DOUBLE_NAN_UPPER_RANGE, POSITIVE_DOUBLE_NAN_LOWER_RANGE,
          NEGATIVE_DOUBLE_NAN_UPPER_RANGE, NEGATIVE_DOUBLE_NAN_LOWER_RANGE,
          Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY);
         ColumnVector result = ColumnVector.spark32BitMurmurHash3(new ColumnVector[]{v});
         ColumnVector expected = ColumnVector.fromBoxedInts(1669671676, 0, -544903190, -1831674681, 150502665, 474144502, 1428788237, 1428788237, 1428788237, 1428788237, 420913893, 1915664072)) {
      assertColumnsAreEqual(expected, result);
    }
  }

  @Test
  void testSpark32BitMurmur3HashTimestamps() {
    // The hash values were derived from Apache Spark in a manner similar to the one documented at
    // https://github.com/rapidsai/cudf/blob/aa7ca46dcd9e/cpp/tests/hashing/hash_test.cpp#L281-L307
    try (ColumnVector v = ColumnVector.timestampMicroSecondsFromBoxedLongs(
        0L, null, 100L, -100L, 0x123456789abcdefL, null, -0x123456789abcdefL);
         ColumnVector result = ColumnVector.spark32BitMurmurHash3(42, new ColumnVector[]{v});
         ColumnVector expected = ColumnVector.fromBoxedInts(-1670924195, 42, 1114849490, 904948192, 657182333, 42, -57193045)) {
      assertColumnsAreEqual(expected, result);
    }
  }

  @Test
  void testSpark32BitMurmur3HashDecimal64() {
    // The hash values were derived from Apache Spark in a manner similar to the one documented at
    // https://github.com/rapidsai/cudf/blob/aa7ca46dcd9e/cpp/tests/hashing/hash_test.cpp#L281-L307
    try (ColumnVector v = ColumnVector.decimalFromLongs(-7,
        0L, 100L, -100L, 0x123456789abcdefL, -0x123456789abcdefL);
         ColumnVector result = ColumnVector.spark32BitMurmurHash3(42, new ColumnVector[]{v});
         ColumnVector expected = ColumnVector.fromBoxedInts(-1670924195, 1114849490, 904948192, 657182333, -57193045)) {
      assertColumnsAreEqual(expected, result);
    }
  }

  @Test
  void testSpark32BitMurmur3HashDecimal32() {
    // The hash values were derived from Apache Spark in a manner similar to the one documented at
    // https://github.com/rapidsai/cudf/blob/aa7ca46dcd9e/cpp/tests/hashing/hash_test.cpp#L281-L307
    try (ColumnVector v = ColumnVector.decimalFromInts(-3,
        0, 100, -100, 0x12345678, -0x12345678);
         ColumnVector result = ColumnVector.spark32BitMurmurHash3(42, new ColumnVector[]{v});
         ColumnVector expected = ColumnVector.fromBoxedInts(-1670924195, 1114849490, 904948192, -958054811, -1447702630)) {
      assertColumnsAreEqual(expected, result);
    }
  }

  @Test
  void testSpark32BitMurmur3HashDates() {
    // The hash values were derived from Apache Spark in a manner similar to the one documented at
    // https://github.com/rapidsai/cudf/blob/aa7ca46dcd9e/cpp/tests/hashing/hash_test.cpp#L281-L307
    try (ColumnVector v = ColumnVector.timestampDaysFromBoxedInts(
        0, null, 100, -100, 0x12345678, null, -0x12345678);
         ColumnVector result = ColumnVector.spark32BitMurmurHash3(42, new ColumnVector[]{v});
         ColumnVector expected = ColumnVector.fromBoxedInts(933211791, 42, 751823303, -1080202046, -1721170160, 42, 1852996993)) {
      assertColumnsAreEqual(expected, result);
    }
  }

  @Test
  void testSpark32BitMurmur3HashFloats() {
    try (ColumnVector v = ColumnVector.fromBoxedFloats(
          0f, 100f, -100f, Float.MIN_NORMAL, Float.MAX_VALUE, null,
          POSITIVE_FLOAT_NAN_LOWER_RANGE, POSITIVE_FLOAT_NAN_UPPER_RANGE,
          NEGATIVE_FLOAT_NAN_LOWER_RANGE, NEGATIVE_FLOAT_NAN_UPPER_RANGE,
          Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY);
         ColumnVector result = ColumnVector.spark32BitMurmurHash3(411, new ColumnVector[]{v});
         ColumnVector expected = ColumnVector.fromBoxedInts(-235179434, 1812056886, 2028471189, 1775092689, -1531511762, 411, -1053523253, -1053523253, -1053523253, -1053523253, -1526256646, 930080402)){
      assertColumnsAreEqual(expected, result);
    }
  }

  @Test
  void testSpark32BitMurmur3HashBools() {
    try (ColumnVector v0 = ColumnVector.fromBoxedBooleans(null, true, false, true, null, false);
         ColumnVector v1 = ColumnVector.fromBoxedBooleans(null, true, false, null, false, true);
         ColumnVector result = ColumnVector.spark32BitMurmurHash3(0, new ColumnVector[]{v0, v1});
         ColumnVector expected = ColumnVector.fromBoxedInts(0, -1589400010, -239939054, -68075478, 593689054, -1194558265)) {
      assertColumnsAreEqual(expected, result);
    }
  }

  @Test
  void testSpark32BitMurmur3HashMixed() {
    try (ColumnVector strings = ColumnVector.fromStrings(
          "a", "B\n", "dE\"\u0100\t\u0101 \ud720\ud721",
          "A very long (greater than 128 bytes/char string) to test a multi hash-step data point " +
          "in the MD5 hash function. This string needed to be longer.",
          null, null);
         ColumnVector integers = ColumnVector.fromBoxedInts(0, 100, -100, Integer.MIN_VALUE, Integer.MAX_VALUE, null);
         ColumnVector doubles = ColumnVector.fromBoxedDoubles(
          0.0, 100.0, -100.0, POSITIVE_DOUBLE_NAN_LOWER_RANGE, POSITIVE_DOUBLE_NAN_UPPER_RANGE, null);
         ColumnVector floats = ColumnVector.fromBoxedFloats(
          0f, 100f, -100f, NEGATIVE_FLOAT_NAN_LOWER_RANGE, NEGATIVE_FLOAT_NAN_UPPER_RANGE, null);
         ColumnVector bools = ColumnVector.fromBoxedBooleans(true, false, null, false, true, null);
         ColumnVector result = ColumnVector.spark32BitMurmurHash3(1868, new ColumnVector[]{strings, integers, doubles, floats, bools});
         ColumnVector expected = ColumnVector.fromBoxedInts(1936985022, 720652989, 339312041, 1400354989, 769988643, 1868)) {
      assertColumnsAreEqual(expected, result);
    }
  }

  @Test
  void testSpark32BitMurmur3HashStruct() {
    try (ColumnVector strings = ColumnVector.fromStrings(
        "a", "B\n", "dE\"\u0100\t\u0101 \ud720\ud721",
        "A very long (greater than 128 bytes/char string) to test a multi hash-step data point " +
            "in the MD5 hash function. This string needed to be longer.",
        null, null);
         ColumnVector integers = ColumnVector.fromBoxedInts(0, 100, -100, Integer.MIN_VALUE, Integer.MAX_VALUE, null);
         ColumnVector doubles = ColumnVector.fromBoxedDoubles(
             0.0, 100.0, -100.0, POSITIVE_DOUBLE_NAN_LOWER_RANGE, POSITIVE_DOUBLE_NAN_UPPER_RANGE, null);
         ColumnVector floats = ColumnVector.fromBoxedFloats(
             0f, 100f, -100f, NEGATIVE_FLOAT_NAN_LOWER_RANGE, NEGATIVE_FLOAT_NAN_UPPER_RANGE, null);
         ColumnVector bools = ColumnVector.fromBoxedBooleans(true, false, null, false, true, null);
         ColumnView structs = ColumnView.makeStructView(strings, integers, doubles, floats, bools);
         ColumnVector result = ColumnVector.spark32BitMurmurHash3(1868, new ColumnView[]{structs});
         ColumnVector expected = ColumnVector.spark32BitMurmurHash3(1868, new ColumnVector[]{strings, integers, doubles, floats, bools})) {
      assertColumnsAreEqual(expected, result);
    }
  }

  @Test
  void testAndNullReconfigureNulls() {
    try (ColumnVector v0 = ColumnVector.fromBoxedInts(0, 100, null, null, Integer.MIN_VALUE, null);
         ColumnVector v1 = ColumnVector.fromBoxedInts(0, 100, 1, 2, Integer.MIN_VALUE, null);
         ColumnVector intResult = v1.mergeAndSetValidity(BinaryOp.BITWISE_AND, v0);
         ColumnVector v2 = ColumnVector.fromStrings("0", "100", "1", "2", "MIN_VALUE", "3");
         ColumnVector stringResult = v2.mergeAndSetValidity(BinaryOp.BITWISE_AND, v0, v1);
         ColumnVector stringExpected = ColumnVector.fromStrings("0", "100", null, null, "MIN_VALUE", null);
         ColumnVector noMaskResult = v2.mergeAndSetValidity(BinaryOp.BITWISE_AND)) {
      assertColumnsAreEqual(v0, intResult);
      assertColumnsAreEqual(stringExpected, stringResult);
      assertColumnsAreEqual(v2, noMaskResult);
    }
  }

  @Test
  void testOrNullReconfigureNulls() {
    try (ColumnVector v0 = ColumnVector.fromBoxedInts(0, 100, null, null, Integer.MIN_VALUE, null);
         ColumnVector v1 = ColumnVector.fromBoxedInts(0, 100, 1, 2, Integer.MIN_VALUE, null);
         ColumnVector v2 = ColumnVector.fromBoxedInts(0, 100, 1, 2, Integer.MIN_VALUE, Integer.MAX_VALUE);
         ColumnVector intResultV0 = v1.mergeAndSetValidity(BinaryOp.BITWISE_OR, v0);
         ColumnVector intResultV0V1 = v1.mergeAndSetValidity(BinaryOp.BITWISE_OR, v0, v1);
         ColumnVector intResultMulti = v1.mergeAndSetValidity(BinaryOp.BITWISE_OR, v0, v0, v1, v1, v0, v1, v0);
         ColumnVector intResultv0v1v2 = v2.mergeAndSetValidity(BinaryOp.BITWISE_OR, v0, v1, v2);
         ColumnVector v3 = ColumnVector.fromStrings("0", "100", "1", "2", "MIN_VALUE", "3");
         ColumnVector stringResult = v3.mergeAndSetValidity(BinaryOp.BITWISE_OR, v0, v1);
         ColumnVector stringExpected = ColumnVector.fromStrings("0", "100", "1", "2", "MIN_VALUE", null);
         ColumnVector noMaskResult = v3.mergeAndSetValidity(BinaryOp.BITWISE_OR)) {
      assertColumnsAreEqual(v0, intResultV0);
      assertColumnsAreEqual(v1, intResultV0V1);
      assertColumnsAreEqual(v1, intResultMulti);
      assertColumnsAreEqual(v2, intResultv0v1v2);
      assertColumnsAreEqual(stringExpected, stringResult);
      assertColumnsAreEqual(v3, noMaskResult);
    }
  }

  @Test
  void isNotNullTestEmptyColumn() {
    try (ColumnVector v = ColumnVector.fromBoxedInts();
         ColumnVector expected = ColumnVector.fromBoxedBooleans();
         ColumnVector result = v.isNotNull()) {
      assertColumnsAreEqual(expected, result);
    }
  }

  @Test
  void isNotNullTest() {
    try (ColumnVector v = ColumnVector.fromBoxedInts(1, 2, null, 4, null, 6);
         ColumnVector expected = ColumnVector.fromBoxedBooleans(true, true, false, true, false, true);
         ColumnVector result = v.isNotNull()) {
      assertColumnsAreEqual(expected, result);
    }
  }

  @Test
  void isNotNullTestAllNulls() {
    try (ColumnVector v = ColumnVector.fromBoxedInts(null, null, null, null, null, null);
         ColumnVector expected = ColumnVector.fromBoxedBooleans(false, false, false, false, false, false);
         ColumnVector result = v.isNotNull()) {
      assertColumnsAreEqual(expected, result);
    }
  }

  @Test
  void isNotNullTestAllNotNulls() {
    try (ColumnVector v = ColumnVector.fromBoxedInts(1,2,3,4,5,6);
         ColumnVector expected = ColumnVector.fromBoxedBooleans(true, true, true, true, true, true);
         ColumnVector result = v.isNotNull()) {
      assertColumnsAreEqual(expected, result);
    }
  }

  @Test
  void isNullTest() {
    try (ColumnVector v = ColumnVector.fromBoxedInts(1, 2, null, 4, null, 6);
         ColumnVector expected = ColumnVector.fromBoxedBooleans(false, false, true, false, true, false);
         ColumnVector result = v.isNull()) {
      assertColumnsAreEqual(expected, result);
    }
  }

  @Test
  void isNullTestEmptyColumn() {
    try (ColumnVector v = ColumnVector.fromBoxedInts();
         ColumnVector expected = ColumnVector.fromBoxedBooleans();
         ColumnVector result = v.isNull()) {
      assertColumnsAreEqual(expected, result);
    }
  }

   @Test
  void isNanTestWithNulls() {
    try (ColumnVector v = ColumnVector.fromBoxedDoubles(null, null, Double.NaN, null, Double.NaN, null);
         ColumnVector vF = ColumnVector.fromBoxedFloats(null, null, Float.NaN, null, Float.NaN, null);
         ColumnVector expected = ColumnVector.fromBoxedBooleans(false, false, true, false, true, false);
         ColumnVector result = v.isNan();
         ColumnVector resultF = vF.isNan()) {
      assertColumnsAreEqual(expected, result);
      assertColumnsAreEqual(expected, resultF);
    }
  }

  @Test
  void isNanForTypeMismatch() {
    assertThrows(CudfException.class, () -> {
      try (ColumnVector v = ColumnVector.fromStrings("foo", "bar", "baz");
           ColumnVector result = v.isNan()) {}
    });
  }

  @Test
  void isNanTest() {
    try (ColumnVector v = ColumnVector.fromBoxedDoubles(1.0, 2.0, Double.NaN, 4.0, Double.NaN, 6.0);
         ColumnVector vF = ColumnVector.fromBoxedFloats(1.1f, 2.2f, Float.NaN, 4.4f, Float.NaN, 6.6f);
         ColumnVector expected = ColumnVector.fromBoxedBooleans(false, false, true, false, true, false);
         ColumnVector result = v.isNan();
         ColumnVector resultF = vF.isNan()) {
      assertColumnsAreEqual(expected, result);
      assertColumnsAreEqual(expected, resultF);
    }
  }

  @Test
  void isNanTestEmptyColumn() {
    try (ColumnVector v = ColumnVector.fromBoxedDoubles();
         ColumnVector vF = ColumnVector.fromBoxedFloats();
         ColumnVector expected = ColumnVector.fromBoxedBooleans();
         ColumnVector result = v.isNan();
         ColumnVector resultF = vF.isNan()) {
      assertColumnsAreEqual(expected, result);
      assertColumnsAreEqual(expected, resultF);
    }
  }

  @Test
  void isNanTestAllNotNans() {
    try (ColumnVector v = ColumnVector.fromBoxedDoubles(1.0, 2.0, 3.0, 4.0, 5.0, 6.0);
         ColumnVector vF = ColumnVector.fromBoxedFloats(1.1f, 2.2f, 3.3f, 4.4f, 5.5f, 6.6f);
         ColumnVector expected = ColumnVector.fromBoxedBooleans(false, false, false, false, false, false);
         ColumnVector result = v.isNan();
         ColumnVector resultF = vF.isNan()) {
      assertColumnsAreEqual(expected, result);
      assertColumnsAreEqual(expected, resultF);
    }
  }

  @Test
  void isNanTestAllNans() {
    try (ColumnVector v = ColumnVector.fromBoxedDoubles(Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
         ColumnVector vF = ColumnVector.fromBoxedFloats(Float.NaN, Float.NaN, Float.NaN, Float.NaN, Float.NaN, Float.NaN);
         ColumnVector expected = ColumnVector.fromBoxedBooleans(true, true, true, true, true, true);
         ColumnVector result = v.isNan();
         ColumnVector resultF = vF.isNan()) {
      assertColumnsAreEqual(expected, result);
      assertColumnsAreEqual(expected, resultF);
    }
  }

  @Test
  void isNotNanTestWithNulls() {
    try (ColumnVector v = ColumnVector.fromBoxedDoubles(null, null, Double.NaN, null, Double.NaN, null);
         ColumnVector vF = ColumnVector.fromBoxedFloats(null, null, Float.NaN, null, Float.NaN, null);
         ColumnVector expected = ColumnVector.fromBoxedBooleans(true, true, false, true, false, true);
         ColumnVector result = v.isNotNan();
         ColumnVector resultF = vF.isNotNan()) {
      assertColumnsAreEqual(expected, result);
      assertColumnsAreEqual(expected, resultF);
    }
  }

  @Test
  void isNotNanForTypeMismatch() {
    assertThrows(CudfException.class, () -> {
      try (ColumnVector v = ColumnVector.fromStrings("foo", "bar", "baz");
           ColumnVector result = v.isNotNan()) {}
    });
  }

  @Test
  void isNotNanTest() {
    try (ColumnVector v = ColumnVector.fromBoxedDoubles(1.0, 2.0, Double.NaN, 4.0, Double.NaN, 6.0);
         ColumnVector vF = ColumnVector.fromBoxedFloats(1.1f, 2.2f, Float.NaN, 4.4f, Float.NaN, 6.6f);
         ColumnVector expected = ColumnVector.fromBoxedBooleans(true, true, false, true, false, true);
         ColumnVector result = v.isNotNan();
         ColumnVector resultF = vF.isNotNan()) {
      assertColumnsAreEqual(expected, result);
      assertColumnsAreEqual(expected, resultF);
    }
  }

  @Test
  void isNotNanTestEmptyColumn() {
    try (ColumnVector v = ColumnVector.fromBoxedDoubles();
         ColumnVector vF = ColumnVector.fromBoxedFloats();
         ColumnVector expected = ColumnVector.fromBoxedBooleans();
         ColumnVector result = v.isNotNan();
         ColumnVector resultF = vF.isNotNan()) {
      assertColumnsAreEqual(expected, result);
      assertColumnsAreEqual(expected, resultF);
    }
  }

  @Test
  void isNotNanTestAllNotNans() {
    try (ColumnVector v = ColumnVector.fromBoxedDoubles(1.0, 2.0, 3.0, 4.0, 5.0, 6.0);
         ColumnVector vF = ColumnVector.fromBoxedFloats(1.1f, 2.2f, 3.3f, 4.4f, 5.5f, 6.6f);
         ColumnVector expected = ColumnVector.fromBoxedBooleans(true, true, true, true, true, true);
         ColumnVector result = v.isNotNan();
         ColumnVector resultF = vF.isNotNan()) {
      assertColumnsAreEqual(expected, result);
      assertColumnsAreEqual(expected, resultF);
    }
  }

  @Test
  void isNotNanTestAllNans() {
    try (ColumnVector v = ColumnVector.fromBoxedDoubles(Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
         ColumnVector vF = ColumnVector.fromBoxedFloats(Float.NaN, Float.NaN, Float.NaN, Float.NaN, Float.NaN, Float.NaN);
         ColumnVector expected = ColumnVector.fromBoxedBooleans(false, false, false, false, false, false);
         ColumnVector result = v.isNotNan();
         ColumnVector resultF = vF.isNotNan()) {
      assertColumnsAreEqual(expected, result);
      assertColumnsAreEqual(expected, resultF);
    }
  }

  @Test
  void roundFloatsHalfUp() {
    try (ColumnVector v = ColumnVector.fromBoxedFloats(1.234f, 25.66f, null, 154.9f, 2346f);
         ColumnVector result1 = v.round();
         ColumnVector result2 = v.round(1, RoundMode.HALF_UP);
         ColumnVector result3 = v.round(-1, RoundMode.HALF_UP);
         ColumnVector expected1 = ColumnVector.fromBoxedFloats(1f, 26f, null, 155f, 2346f);
         ColumnVector expected2 = ColumnVector.fromBoxedFloats(1.2f, 25.7f, null, 154.9f, 2346f);
         ColumnVector expected3 = ColumnVector.fromBoxedFloats(0f, 30f, null, 150f, 2350f)) {
      assertColumnsAreEqual(expected1, result1);
      assertColumnsAreEqual(expected2, result2);
      assertColumnsAreEqual(expected3, result3);
    }
  }

  @Test
  void roundFloatsHalfEven() {
    try (ColumnVector v = ColumnVector.fromBoxedFloats(1.5f, 2.5f, 1.35f, null, 1.25f, 15f, 25f);
         ColumnVector result1 = v.round(RoundMode.HALF_EVEN);
         ColumnVector result2 = v.round(1, RoundMode.HALF_EVEN);
         ColumnVector result3 = v.round(-1, RoundMode.HALF_EVEN);
         ColumnVector expected1 = ColumnVector.fromBoxedFloats(2f, 2f, 1f, null, 1f, 15f, 25f);
         ColumnVector expected2 = ColumnVector.fromBoxedFloats(1.5f, 2.5f, 1.4f, null, 1.2f, 15f, 25f);
         ColumnVector expected3 = ColumnVector.fromBoxedFloats(0f, 0f, 0f, null, 0f, 20f, 20f)) {
      assertColumnsAreEqual(expected1, result1);
      assertColumnsAreEqual(expected2, result2);
      assertColumnsAreEqual(expected3, result3);
    }
  }

  @Test
  void roundIntsHalfUp() {
    try (ColumnVector v = ColumnVector.fromBoxedInts(12, 135, 160, -1454, null, -1500, -140, -150);
         ColumnVector result1 = v.round(2, RoundMode.HALF_UP);
         ColumnVector result2 = v.round(-2, RoundMode.HALF_UP);
         ColumnVector expected1 = ColumnVector.fromBoxedInts(12, 135, 160, -1454, null, -1500, -140, -150);
         ColumnVector expected2 = ColumnVector.fromBoxedInts(0, 100, 200, -1500, null, -1500, -100, -200)) {
      assertColumnsAreEqual(expected1, result1);
      assertColumnsAreEqual(expected2, result2);
    }
  }

  @Test
  void roundIntsHalfEven() {
    try (ColumnVector v = ColumnVector.fromBoxedInts(12, 24, 135, 160, null, 1450, 1550, -1650);
         ColumnVector result1 = v.round(2, RoundMode.HALF_EVEN);
         ColumnVector result2 = v.round(-2, RoundMode.HALF_EVEN);
         ColumnVector expected1 = ColumnVector.fromBoxedInts(12, 24, 135, 160, null, 1450, 1550, -1650);
         ColumnVector expected2 = ColumnVector.fromBoxedInts(0, 0, 100, 200, null, 1400, 1600, -1600)) {
      assertColumnsAreEqual(expected1, result1);
      assertColumnsAreEqual(expected2, result2);
    }
  }

  @Test
  void roundDecimal() {
    final int dec32Scale1 = -2;
    final int resultScale1 = -3;

    final int[] DECIMAL32_1 = new int[]{14, 15, 16, 24, 25, 26} ;
    final int[] expectedHalfUp = new int[]{1, 2, 2, 2, 3, 3};
    final int[] expectedHalfEven = new int[]{1, 2, 2, 2, 2, 3};
    try (ColumnVector v = ColumnVector.decimalFromInts(-dec32Scale1, DECIMAL32_1);
         ColumnVector roundHalfUp = v.round(-3, RoundMode.HALF_UP);
         ColumnVector roundHalfEven = v.round(-3, RoundMode.HALF_EVEN);
         ColumnVector answerHalfUp = ColumnVector.decimalFromInts(-resultScale1, expectedHalfUp);
         ColumnVector answerHalfEven = ColumnVector.decimalFromInts(-resultScale1, expectedHalfEven)) {
      assertColumnsAreEqual(answerHalfUp, roundHalfUp);
      assertColumnsAreEqual(answerHalfEven, roundHalfEven);
    }
  }

  @Test
  void testGetDeviceMemorySizeNonStrings() {
    try (ColumnVector v0 = ColumnVector.fromBoxedInts(1, 2, 3, 4, 5, 6);
         ColumnVector v1 = ColumnVector.fromBoxedInts(1, 2, 3, null, null, 4, 5, 6)) {
      assertEquals(24, v0.getDeviceMemorySize()); // (6*4B)
      assertEquals(96, v1.getDeviceMemorySize()); // (8*4B) + 64B(for validity vector)
    }
  }

  @Test
  void testGetDeviceMemorySizeStrings() {
    try (ColumnVector v0 = ColumnVector.fromStrings("onetwothree", "four", "five");
         ColumnVector v1 = ColumnVector.fromStrings("onetwothree", "four", null, "five")) {
      assertEquals(35, v0.getDeviceMemorySize()); //19B data + 4*4B offsets = 35
      assertEquals(103, v1.getDeviceMemorySize()); //19B data + 5*4B + 64B validity vector = 103B
    }
  }

  @SuppressWarnings("unchecked")
  @Test
  void testGetDeviceMemorySizeLists() {
    DataType svType = new ListType(true, new BasicType(true, DType.STRING));
    DataType ivType = new ListType(false, new BasicType(false, DType.INT32));
    try (ColumnVector sv = ColumnVector.fromLists(svType,
        Arrays.asList("first", "second", "third"),
        Arrays.asList("fourth", null),
        null);
         ColumnVector iv = ColumnVector.fromLists(ivType,
             Arrays.asList(1, 2, 3),
             Collections.singletonList(4),
             Arrays.asList(5, 6),
             Collections.singletonList(7))) {
      // 64 bytes for validity of list column
      // 16 bytes for offsets of list column
      // 64 bytes for validity of string column
      // 24 bytes for offsets of of string column
      // 22 bytes of string character size
      assertEquals(64+16+64+24+22, sv.getDeviceMemorySize());

      // 20 bytes for offsets of list column
      // 28 bytes for data of INT32 column
      assertEquals(20+28, iv.getDeviceMemorySize());
    }
  }

  @Test
  void testGetDeviceMemorySizeStructs() {
    DataType structType = new StructType(true,
        new ListType(true, new BasicType(true, DType.STRING)),
        new BasicType(true, DType.INT64));
    try (ColumnVector v = ColumnVector.fromStructs(structType,
        new StructData(
            Arrays.asList("first", "second", "third"),
            10L),
        new StructData(
            Arrays.asList("fourth", null),
            20L),
        new StructData(
            null,
            null),
        null)) {
      // 64 bytes for validity of the struct column
      // 64 bytes for validity of list column
      // 20 bytes for offsets of list column
      // 64 bytes for validity of string column
      // 28 bytes for offsets of of string column
      // 22 bytes of string character size
      // 64 bytes for validity of int64 column
      // 28 bytes for data of the int64 column
      assertEquals(64+64+20+64+28+22+64+28, v.getDeviceMemorySize());
    }
  }

  @Test
  void testSequenceInt() {
    try (Scalar zero = Scalar.fromInt(0);
         Scalar one = Scalar.fromInt(1);
         Scalar negOne = Scalar.fromInt(-1);
         Scalar nulls = Scalar.fromNull(DType.INT32)) {

      try (
          ColumnVector cv = ColumnVector.sequence(zero, 5);
          ColumnVector expected = ColumnVector.fromInts(0, 1, 2, 3, 4)) {
        assertColumnsAreEqual(expected, cv);
      }


      try (ColumnVector cv = ColumnVector.sequence(one, negOne,6);
           ColumnVector expected = ColumnVector.fromInts(1, 0, -1, -2, -3, -4)) {
        assertColumnsAreEqual(expected, cv);
      }

      try (ColumnVector cv = ColumnVector.sequence(zero, 0);
           ColumnVector expected = ColumnVector.fromInts()) {
        assertColumnsAreEqual(expected, cv);
      }

      assertThrows(IllegalArgumentException.class, () -> {
        try (ColumnVector cv = ColumnVector.sequence(nulls, 5)) { }
      });

      assertThrows(CudfException.class, () -> {
        try (ColumnVector cv = ColumnVector.sequence(zero, -3)) { }
      });
    }
  }

  @Test
  void testSequenceDouble() {
    try (Scalar zero = Scalar.fromDouble(0.0);
         Scalar one = Scalar.fromDouble(1.0);
         Scalar negOneDotOne = Scalar.fromDouble(-1.1)) {

      try (
          ColumnVector cv = ColumnVector.sequence(zero, 5);
          ColumnVector expected = ColumnVector.fromDoubles(0, 1, 2, 3, 4)) {
        assertColumnsAreEqual(expected, cv);
      }


      try (ColumnVector cv = ColumnVector.sequence(one, negOneDotOne,6);
           ColumnVector expected = ColumnVector.fromDoubles(1, -0.1, -1.2, -2.3, -3.4, -4.5)) {
        assertColumnsAreEqual(expected, cv);
      }

      try (ColumnVector cv = ColumnVector.sequence(zero, 0);
           ColumnVector expected = ColumnVector.fromDoubles()) {
        assertColumnsAreEqual(expected, cv);
      }
    }
  }

  @Test
  void testSequenceOtherTypes() {
    assertThrows(CudfException.class, () -> {
      try (Scalar s = Scalar.fromString("0");
      ColumnVector cv = ColumnVector.sequence(s, s, 5)) {}
    });

    assertThrows(CudfException.class, () -> {
      try (Scalar s = Scalar.fromBool(false);
           ColumnVector cv = ColumnVector.sequence(s, s, 5)) {}
    });

    assertThrows(CudfException.class, () -> {
      try (Scalar s = Scalar.timestampDaysFromInt(100);
           ColumnVector cv = ColumnVector.sequence(s, s, 5)) {}
    });
  }

  @Test
  void testFromScalarZeroRows() {
    // magic number to invoke factory method specialized for decimal types
    int mockScale = -8;
    for (DType.DTypeEnum type : DType.DTypeEnum.values()) {
      Scalar s = null;
      try {
        switch (type) {
        case BOOL8:
          s = Scalar.fromBool(true);
          break;
        case INT8:
          s = Scalar.fromByte((byte) 5);
          break;
        case UINT8:
          s = Scalar.fromUnsignedByte((byte) 254);
          break;
        case INT16:
          s = Scalar.fromShort((short) 12345);
          break;
        case UINT16:
          s = Scalar.fromUnsignedShort((short) 65432);
          break;
        case INT32:
          s = Scalar.fromInt(123456789);
          break;
        case UINT32:
          s = Scalar.fromUnsignedInt(0xfedcba98);
          break;
        case INT64:
          s = Scalar.fromLong(1234567890123456789L);
          break;
        case UINT64:
          s = Scalar.fromUnsignedLong(0xfedcba9876543210L);
          break;
        case FLOAT32:
          s = Scalar.fromFloat(1.2345f);
          break;
        case FLOAT64:
          s = Scalar.fromDouble(1.23456789);
          break;
        case DECIMAL32:
          s = Scalar.fromDecimal(mockScale, 123456789);
          break;
        case DECIMAL64:
          s = Scalar.fromDecimal(mockScale, 1234567890123456789L);
          break;
        case TIMESTAMP_DAYS:
          s = Scalar.timestampDaysFromInt(12345);
          break;
        case TIMESTAMP_SECONDS:
        case TIMESTAMP_MILLISECONDS:
        case TIMESTAMP_MICROSECONDS:
        case TIMESTAMP_NANOSECONDS:
          s = Scalar.timestampFromLong(DType.create(type), 1234567890123456789L);
          break;
        case STRING:
          s = Scalar.fromString("hello, world!");
          break;
        case DURATION_DAYS:
          s = Scalar.durationDaysFromInt(3);
          break;
        case DURATION_SECONDS:
        case DURATION_MILLISECONDS:
        case DURATION_MICROSECONDS:
        case DURATION_NANOSECONDS:
          s = Scalar.durationFromLong(DType.create(type), 21313);
          break;
        case EMPTY:
          continue;
        case STRUCT:
          try (ColumnVector col1 = ColumnVector.fromInts(1);
               ColumnVector col2 = ColumnVector.fromStrings("A");
               ColumnVector col3 = ColumnVector.fromDoubles(1.23)) {
            s = Scalar.structFromColumnViews(col1, col2, col3);
          }
          break;
        case LIST:
          try (ColumnVector list = ColumnVector.fromInts(1, 2, 3)) {
            s = Scalar.listFromColumnView(list);
          }
          break;
        default:
          throw new IllegalArgumentException("Unexpected type: " + type);
        }

        try (ColumnVector c = ColumnVector.fromScalar(s, 0)) {
          if (type.isDecimalType()) {
            assertEquals(DType.create(type, mockScale), c.getType());
          } else {
            assertEquals(DType.create(type), c.getType());
          }
          assertEquals(0, c.getRowCount());
          assertEquals(0, c.getNullCount());
        }
      } finally {
        if (s != null) {
          s.close();
        }
      }
    }
  }

  @Test
  void testGetNativeView() {
    try (ColumnVector cv = ColumnVector.fromInts(1, 3, 4, 5)) {
      //not a real test whats being returned is a view but this is the best we can do
      assertNotEquals(0L, cv.getNativeView());
    }
  }

  @Test
  void testFromScalar() {
    final int rowCount = 4;
    for (DType.DTypeEnum type : DType.DTypeEnum.values()) {
      if(type.isDecimalType()) {
        continue;
      }
      Scalar s = null;
      ColumnVector expected = null;
      ColumnVector result = null;
      try {
        switch (type) {
        case BOOL8:
          s = Scalar.fromBool(true);
          expected = ColumnVector.fromBoxedBooleans(true, true, true, true);
          break;
        case INT8: {
          byte v = (byte) 5;
          s = Scalar.fromByte(v);
          expected = ColumnVector.fromBoxedBytes(v, v, v, v);
          break;
        }
        case UINT8: {
          byte v = (byte) 254;
          s = Scalar.fromUnsignedByte(v);
          expected = ColumnVector.fromBoxedUnsignedBytes(v, v, v, v);
          break;
        }
        case INT16: {
          short v = (short) 12345;
          s = Scalar.fromShort(v);
          expected = ColumnVector.fromBoxedShorts(v, v, v, v);
          break;
        }
        case UINT16: {
          short v = (short) 0x89ab;
          s = Scalar.fromUnsignedShort(v);
          expected = ColumnVector.fromBoxedUnsignedShorts(v, v, v, v);
          break;
        }
        case INT32: {
          int v = 123456789;
          s = Scalar.fromInt(v);
          expected = ColumnVector.fromBoxedInts(v, v, v, v);
          break;
        }
        case UINT32: {
          int v = 0x89abcdef;
          s = Scalar.fromUnsignedInt(v);
          expected = ColumnVector.fromBoxedUnsignedInts(v, v, v, v);
          break;
        }
        case INT64: {
          long v = 1234567890123456789L;
          s = Scalar.fromLong(v);
          expected = ColumnVector.fromBoxedLongs(v, v, v, v);
          break;
        }
        case UINT64: {
          long v = 0xfedcba9876543210L;
          s = Scalar.fromUnsignedLong(v);
          expected = ColumnVector.fromBoxedUnsignedLongs(v, v, v, v);
          break;
        }
        case FLOAT32: {
          float v = 1.2345f;
          s = Scalar.fromFloat(v);
          expected = ColumnVector.fromBoxedFloats(v, v, v, v);
          break;
        }
        case FLOAT64: {
          double v = 1.23456789;
          s = Scalar.fromDouble(v);
          expected = ColumnVector.fromBoxedDoubles(v, v, v, v);
          break;
        }
        case TIMESTAMP_DAYS: {
          int v = 12345;
          s = Scalar.timestampDaysFromInt(v);
          expected = ColumnVector.daysFromInts(v, v, v, v);
          break;
        }
        case TIMESTAMP_SECONDS: {
          long v = 1234567890123456789L;
          s = Scalar.timestampFromLong(DType.TIMESTAMP_SECONDS, v);
          expected = ColumnVector.timestampSecondsFromLongs(v, v, v, v);
          break;
        }
        case TIMESTAMP_MILLISECONDS: {
          long v = 1234567890123456789L;
          s = Scalar.timestampFromLong(DType.TIMESTAMP_MILLISECONDS, v);
          expected = ColumnVector.timestampMilliSecondsFromLongs(v, v, v, v);
          break;
        }
        case TIMESTAMP_MICROSECONDS: {
          long v = 1234567890123456789L;
          s = Scalar.timestampFromLong(DType.TIMESTAMP_MICROSECONDS, v);
          expected = ColumnVector.timestampMicroSecondsFromLongs(v, v, v, v);
          break;
        }
        case TIMESTAMP_NANOSECONDS: {
          long v = 1234567890123456789L;
          s = Scalar.timestampFromLong(DType.TIMESTAMP_NANOSECONDS, v);
          expected = ColumnVector.timestampNanoSecondsFromLongs(v, v, v, v);
          break;
        }
        case STRING: {
          String v = "hello, world!";
          s = Scalar.fromString(v);
          expected = ColumnVector.fromStrings(v, v, v, v);
          break;
        }
        case DURATION_DAYS: {
          int v = 13;
          s = Scalar.durationDaysFromInt(v);
          expected = ColumnVector.durationDaysFromInts(v, v, v, v);
          break;
        }
        case DURATION_MICROSECONDS: {
          long v = 1123123123L;
          s = Scalar.durationFromLong(DType.DURATION_MICROSECONDS, v);
          expected = ColumnVector.durationMicroSecondsFromLongs(v, v, v, v);
          break;
        }
        case DURATION_MILLISECONDS: {
          long v = 11212432423L;
          s = Scalar.durationFromLong(DType.DURATION_MILLISECONDS, v);
          expected = ColumnVector.durationMilliSecondsFromLongs(v, v, v, v);
          break;
        }
        case DURATION_NANOSECONDS: {
          long v = 12353245343L;
          s = Scalar.durationFromLong(DType.DURATION_NANOSECONDS, v);
          expected = ColumnVector.durationNanoSecondsFromLongs(v, v, v, v);
          break;
        }
        case DURATION_SECONDS: {
          long v = 132342321123L;
          s = Scalar.durationFromLong(DType.DURATION_SECONDS, v);
          expected = ColumnVector.durationSecondsFromLongs(v, v, v, v);
          break;
        }
        case EMPTY:
          continue;
        case STRUCT:
          try (ColumnVector col0 = ColumnVector.fromInts(1);
               ColumnVector col1 = ColumnVector.fromBoxedDoubles((Double) null);
               ColumnVector col2 = ColumnVector.fromStrings("a");
               ColumnVector col3 = ColumnVector.fromDecimals(BigDecimal.TEN);
               ColumnVector col4 = ColumnVector.daysFromInts(10)) {
            s = Scalar.structFromColumnViews(col0, col1, col2, col3, col4);
            StructData structData = new StructData(1, null, "a", BigDecimal.TEN, 10);
            expected = ColumnVector.fromStructs(new HostColumnVector.StructType(true,
                    new HostColumnVector.BasicType(true, DType.INT32),
                    new HostColumnVector.BasicType(true, DType.FLOAT64),
                    new HostColumnVector.BasicType(true, DType.STRING),
                    new HostColumnVector.BasicType(true, DType.create(DType.DTypeEnum.DECIMAL32, 0)),
                    new HostColumnVector.BasicType(true, DType.TIMESTAMP_DAYS)),
                structData, structData, structData, structData);
          }
          break;
        case LIST:
          try (ColumnVector list = ColumnVector.fromInts(1, 2, 3)) {
            s = Scalar.listFromColumnView(list);
            expected = ColumnVector.fromLists(
                new HostColumnVector.ListType(true,
                    new HostColumnVector.BasicType(true, DType.INT32)),
                Arrays.asList(1, 2, 3),
                Arrays.asList(1, 2, 3),
                Arrays.asList(1, 2, 3),
                Arrays.asList(1, 2, 3));
          }
          break;
        default:
          throw new IllegalArgumentException("Unexpected type: " + type);
        }

        result = ColumnVector.fromScalar(s, rowCount);
        assertColumnsAreEqual(expected, result);
      } finally {
        if (s != null) {
          s.close();
        }
        if (expected != null) {
          expected.close();
        }
        if (result != null) {
          result.close();
        }
      }
    }
  }

  @Test
  void testFromScalarNull() {
    final int rowCount = 4;
    for (DType.DTypeEnum typeEnum : DType.DTypeEnum.values()) {
      if (typeEnum == DType.DTypeEnum.EMPTY || typeEnum == DType.DTypeEnum.LIST || typeEnum == DType.DTypeEnum.STRUCT) {
        continue;
      }
      DType dType;
      if (typeEnum.isDecimalType()) {
        // magic number to invoke factory method specialized for decimal types
        dType = DType.create(typeEnum, -8);
      } else {
        dType = DType.create(typeEnum);
      }
      try (Scalar s = Scalar.fromNull(dType);
           ColumnVector c = ColumnVector.fromScalar(s, rowCount);
           HostColumnVector hc = c.copyToHost()) {
        assertEquals(typeEnum, c.getType().typeId);
        assertEquals(rowCount, c.getRowCount());
        assertEquals(rowCount, c.getNullCount());
        for (int i = 0; i < rowCount; ++i) {
          assertTrue(hc.isNull(i));
        }
      }
    }
  }

  @Test
  void testFromScalarNullByte() {
    int numNulls = 3000;
    try (Scalar s = Scalar.fromNull(DType.INT8);
         ColumnVector tmp = ColumnVector.fromScalar(s, numNulls);
         HostColumnVector input = tmp.copyToHost()) {
      assertEquals(numNulls, input.getRowCount());
      assertEquals(input.getNullCount(), numNulls);
      for (int i = 0; i < numNulls; i++){
        assertTrue(input.isNull(i));
      }
    }
  }

  @Test
  void testFromScalarNullList() {
    final int rowCount = 4;
    for (DType.DTypeEnum typeEnum : DType.DTypeEnum.values()) {
      DType dType = typeEnum.isDecimalType() ? DType.create(typeEnum, -8): DType.create(typeEnum);
      DataType hDataType;
      if (DType.EMPTY.equals(dType)) {
        continue;
      } else if (DType.LIST.equals(dType)) {
        // list of list of int32
        hDataType = new ListType(true, new BasicType(true, DType.INT32));
      } else if (DType.STRUCT.equals(dType)) {
        // list of struct of int32
        hDataType = new StructType(true, new BasicType(true, DType.INT32));
      } else {
        // list of non nested type
        hDataType = new BasicType(true, dType);
      }
      try (Scalar s = Scalar.listFromNull(hDataType);
           ColumnVector c = ColumnVector.fromScalar(s, rowCount);
           HostColumnVector hc = c.copyToHost()) {
        assertEquals(DType.LIST, c.getType());
        assertEquals(rowCount, c.getRowCount());
        assertEquals(rowCount, c.getNullCount());
        for (int i = 0; i < rowCount; ++i) {
          assertTrue(hc.isNull(i));
        }

        try (ColumnView child = c.getChildColumnView(0)) {
          assertEquals(dType, child.getType());
          assertEquals(0L, child.getRowCount());
          assertEquals(0L, child.getNullCount());
          if (child.getType().isNestedType()) {
            try (ColumnView grandson = child.getChildColumnView(0)) {
              assertEquals(DType.INT32, grandson.getType());
              assertEquals(0L, grandson.getRowCount());
              assertEquals(0L, grandson.getNullCount());
            }
          }
        }
      }
    }
  }

  @Test
  void testFromScalarListOfList() {
    HostColumnVector.DataType childType = new HostColumnVector.ListType(true,
        new HostColumnVector.BasicType(true, DType.INT32));
    HostColumnVector.DataType resultType = new HostColumnVector.ListType(true, childType);
    try (ColumnVector list = ColumnVector.fromLists(childType,
             Arrays.asList(1, 2, 3),
             Arrays.asList(4, 5, 6));
         Scalar s = Scalar.listFromColumnView(list)) {
      try (ColumnVector ret = ColumnVector.fromScalar(s, 2);
           ColumnVector expected = ColumnVector.fromLists(resultType,
                   Arrays.asList(Arrays.asList(1, 2, 3),Arrays.asList(4, 5, 6)),
                   Arrays.asList(Arrays.asList(1, 2, 3),Arrays.asList(4, 5, 6)))) {
        assertColumnsAreEqual(expected, ret);
      }
      // empty row
      try (ColumnVector ret = ColumnVector.fromScalar(s, 0)) {
        assertEquals(ret.getRowCount(), 0);
        assertEquals(ret.getNullCount(), 0);
      }
    }
  }

  @Test
  void testFromScalarListOfStruct() {
    HostColumnVector.DataType childType = new HostColumnVector.StructType(true,
            new HostColumnVector.BasicType(true, DType.INT32),
            new HostColumnVector.BasicType(true, DType.STRING));
    HostColumnVector.DataType resultType = new HostColumnVector.ListType(true, childType);
    try (ColumnVector list = ColumnVector.fromStructs(childType,
            new HostColumnVector.StructData(1, "s1"),
            new HostColumnVector.StructData(2, "s2"));
         Scalar s = Scalar.listFromColumnView(list)) {
      try (ColumnVector ret = ColumnVector.fromScalar(s, 2);
           ColumnVector expected = ColumnVector.fromLists(resultType,
                   Arrays.asList(new HostColumnVector.StructData(1, "s1"),
                                 new HostColumnVector.StructData(2, "s2")),
                   Arrays.asList(new HostColumnVector.StructData(1, "s1"),
                                 new HostColumnVector.StructData(2, "s2")))) {
        assertColumnsAreEqual(expected, ret);
      }
      // empty row
      try (ColumnVector ret = ColumnVector.fromScalar(s, 0)) {
        assertEquals(ret.getRowCount(), 0);
        assertEquals(ret.getNullCount(), 0);
      }
    }
  }

  @Test
  void testFromScalarNullStruct() {
    final int rowCount = 4;
    for (DType.DTypeEnum typeEnum : DType.DTypeEnum.values()) {
      DType dType = typeEnum.isDecimalType() ? DType.create(typeEnum, -8) : DType.create(typeEnum);
      DataType hDataType;
      if (DType.EMPTY.equals(dType)) {
        continue;
      } else if (DType.LIST.equals(dType)) {
        // list of list of int32
        hDataType = new ListType(true, new BasicType(true, DType.INT32));
      } else if (DType.STRUCT.equals(dType)) {
        // list of struct of int32
        hDataType = new StructType(true, new BasicType(true, DType.INT32));
      } else {
        // list of non nested type
        hDataType = new BasicType(true, dType);
      }
      try (Scalar s = Scalar.structFromNull(hDataType, hDataType, hDataType);
           ColumnVector c = ColumnVector.fromScalar(s, rowCount);
           HostColumnVector hc = c.copyToHost()) {
        assertEquals(DType.STRUCT, c.getType());
        assertEquals(rowCount, c.getRowCount());
        assertEquals(rowCount, c.getNullCount());
        for (int i = 0; i < rowCount; ++i) {
          assertTrue(hc.isNull(i));
        }
        assertEquals(3, c.getNumChildren());
        ColumnView[] children = new ColumnView[]{c.getChildColumnView(0),
            c.getChildColumnView(1), c.getChildColumnView(2)};
        try {
          for (ColumnView child : children) {
            assertEquals(dType, child.getType());
            assertEquals(rowCount, child.getRowCount());
            assertEquals(rowCount, child.getNullCount());
            if (child.getType() == DType.LIST) {
              try (ColumnView childOfChild = child.getChildColumnView(0)) {
                assertEquals(DType.INT32, childOfChild.getType());
                assertEquals(0L, childOfChild.getRowCount());
                assertEquals(0L, childOfChild.getNullCount());
              }
            } else if (child.getType() == DType.STRUCT) {
              assertEquals(1, child.getNumChildren());
              try (ColumnView childOfChild = child.getChildColumnView(0)) {
                assertEquals(DType.INT32, childOfChild.getType());
                assertEquals(rowCount, childOfChild.getRowCount());
                assertEquals(rowCount, childOfChild.getNullCount());
              }
            }
          }
        } finally {
          for (ColumnView cv : children) cv.close();
        }
      }
    }
  }

  @Test
  void testReplaceNullsScalarEmptyColumn() {
    try (ColumnVector input = ColumnVector.fromBoxedBooleans();
         ColumnVector expected = ColumnVector.fromBoxedBooleans();
         Scalar s = Scalar.fromBool(false);
         ColumnVector result = input.replaceNulls(s)) {
      assertColumnsAreEqual(expected, result);
    }
  }

  @Test
  void testReplaceNullsScalarBoolsWithAllNulls() {
    try (ColumnVector input = ColumnVector.fromBoxedBooleans(null, null, null, null);
         ColumnVector expected = ColumnVector.fromBoxedBooleans(false, false, false, false);
         Scalar s = Scalar.fromBool(false);
         ColumnVector result = input.replaceNulls(s)) {
      assertColumnsAreEqual(expected, result);
    }
  }

  @Test
  void testReplaceNullsScalarSomeNullBools() {
    try (ColumnVector input = ColumnVector.fromBoxedBooleans(false, null, null, false);
         ColumnVector expected = ColumnVector.fromBoxedBooleans(false, true, true, false);
         Scalar s = Scalar.fromBool(true);
         ColumnVector result = input.replaceNulls(s)) {
      assertColumnsAreEqual(expected, result);
    }
  }

  @Test
  void testReplaceNullsScalarIntegersWithAllNulls() {
    try (ColumnVector input = ColumnVector.fromBoxedInts(null, null, null, null);
         ColumnVector expected = ColumnVector.fromBoxedInts(0, 0, 0, 0);
         Scalar s = Scalar.fromInt(0);
         ColumnVector result = input.replaceNulls(s)) {
      assertColumnsAreEqual(expected, result);
    }
  }

  @Test
  void testReplaceNullsScalarSomeNullIntegers() {
    try (ColumnVector input = ColumnVector.fromBoxedInts(1, 2, null, 4, null);
         ColumnVector expected = ColumnVector.fromBoxedInts(1, 2, 999, 4, 999);
         Scalar s = Scalar.fromInt(999);
         ColumnVector result = input.replaceNulls(s)) {
      assertColumnsAreEqual(expected, result);
    }
  }

  @Test
  void testReplaceNullsScalarFailsOnTypeMismatch() {
    try (ColumnVector input = ColumnVector.fromBoxedInts(1, 2, null, 4, null);
         Scalar s = Scalar.fromBool(true)) {
      assertThrows(CudfException.class, () -> input.replaceNulls(s).close());
    }
  }

  @Test
  void testReplaceNullsWithNullScalar() {
    try (ColumnVector input = ColumnVector.fromBoxedInts(1, 2, null, 4, null);
         Scalar s = Scalar.fromNull(input.getType());
         ColumnVector result = input.replaceNulls(s)) {
      assertColumnsAreEqual(input, result);
    }
  }

  @Test
  void testReplaceNullsPolicy() {
    try (ColumnVector input = ColumnVector.fromBoxedInts(null, 1, 2, null, 4, null);
         ColumnVector preceding = input.replaceNulls(ReplacePolicy.PRECEDING);
         ColumnVector expectedPre = ColumnVector.fromBoxedInts(null, 1, 2, 2, 4, 4);
         ColumnVector following = input.replaceNulls(ReplacePolicy.FOLLOWING);
         ColumnVector expectedFol = ColumnVector.fromBoxedInts(1, 1, 2, 4, 4, null)) {
      assertColumnsAreEqual(expectedPre, preceding);
      assertColumnsAreEqual(expectedFol, following);
    }
  }

  @Test
  void testReplaceNullsColumnEmptyColumn() {
    try (ColumnVector input = ColumnVector.fromBoxedBooleans();
         ColumnVector r = ColumnVector.fromBoxedBooleans();
         ColumnVector expected = ColumnVector.fromBoxedBooleans();
         ColumnVector result = input.replaceNulls(r)) {
      assertColumnsAreEqual(expected, result);
    }
  }

  @Test
  void testReplaceNullsColumnBools() {
    try (ColumnVector input = ColumnVector.fromBoxedBooleans(null, true, null, false);
         ColumnVector r = ColumnVector.fromBoxedBooleans(false, null, true, true);
         ColumnVector expected = ColumnVector.fromBoxedBooleans(false, true, true, false);
         ColumnVector result = input.replaceNulls(r)) {
      assertColumnsAreEqual(expected, result);
    }
  }

  @Test
  void testReplaceNullsColumnIntegers() {
    try (ColumnVector input = ColumnVector.fromBoxedInts(1, 2, null, 4, null);
         ColumnVector r = ColumnVector.fromBoxedInts(996, 997, 998, 909, null);
         ColumnVector expected = ColumnVector.fromBoxedInts(1, 2, 998, 4, null);
         ColumnVector result = input.replaceNulls(r)) {
      assertColumnsAreEqual(expected, result);
    }
  }

  @Test
  void testReplaceNullsColumnFailsOnTypeMismatch() {
    try (ColumnVector input = ColumnVector.fromBoxedInts(1, 2, null, 4, null);
         ColumnVector r = ColumnVector.fromBoxedBooleans(true)) {
      assertThrows(CudfException.class, () -> input.replaceNulls(r).close());
    }
  }

  static QuantileMethod[] methods = {LINEAR, LOWER, HIGHER, MIDPOINT, NEAREST};
  static double[] quantiles = {0.0, 0.25, 0.33, 0.5, 1.0};

  @Test
  void testQuantilesOnIntegerInput() {
    double[][] exactExpected = {
        {-1.0,   1.0,   1.0,   2.5,   9.0},  // LINEAR
        {  -1,     1,     1,     2,     9},  // LOWER
        {  -1,     1,     1,     3,     9},  // HIGHER
        {-1.0,   1.0,   1.0,   2.5,   9.0},  // MIDPOINT
        {  -1,     1,     1,     2,     9}}; // NEAREST

    try (ColumnVector cv = ColumnVector.fromBoxedInts(-1, 0, 1, 1, 2, 3, 4, 6, 7, 9)) {
      for (int i = 0 ; i < methods.length ; i++) {
        try (ColumnVector result = cv.quantile(methods[i], quantiles);
             HostColumnVector hostResult = result.copyToHost()) {
          double[] expected = exactExpected[i];
          assertEquals(expected.length, hostResult.getRowCount());
          for (int j = 0; j < expected.length; j++) {
            assertEqualsWithinPercentage(expected[j], hostResult.getDouble(j), PERCENTAGE, methods[i] + " " + quantiles[j]);
          }
        }
      }
    }
  }

  @Test
  void testQuantilesOnDoubleInput() {
    double[][] exactExpected = {
        {-1.01, 0.8, 0.9984, 2.13, 6.8},  // LINEAR
        {-1.01, 0.8,    0.8, 2.13, 6.8},  // LOWER
        {-1.01, 0.8,   1.11, 2.13, 6.8},  // HIGHER
        {-1.01, 0.8,  0.955, 2.13, 6.8},  // MIDPOINT
        {-1.01, 0.8,   1.11, 2.13, 6.8}}; // NEAREST

    try (ColumnVector cv = ColumnVector.fromBoxedDoubles(-1.01, 0.15, 0.8, 1.11, 2.13, 3.4, 4.17, 5.7, 6.8)) {
      for (int i = 0 ; i < methods.length ; i++) {
        try (ColumnVector result = cv.quantile(methods[i], quantiles);
             HostColumnVector hostResult = result.copyToHost()) {
          double[] expected = exactExpected[i];
          assertEquals(expected.length, hostResult.getRowCount());
          for (int j = 0; j < expected.length; j++) {
            assertEqualsWithinPercentage(expected[j], hostResult.getDouble(j), PERCENTAGE, methods[i] + " " + quantiles[j]);
          }
        }
      }
    }
  }

  @Test
  void testSubvector() {
    try (ColumnVector vec = ColumnVector.fromBoxedInts(1, 2, 3, null, 5);
         ColumnVector expected = ColumnVector.fromBoxedInts(2, 3, null, 5);
         ColumnVector found = vec.subVector(1, 5)) {
      TableTest.assertColumnsAreEqual(expected, found);
    }

    try (ColumnVector vec = ColumnVector.fromStrings("1", "2", "3", null, "5");
         ColumnVector expected = ColumnVector.fromStrings("2", "3", null, "5");
         ColumnVector found = vec.subVector(1, 5)) {
      TableTest.assertColumnsAreEqual(expected, found);
    }
  }

  @Test
  void testSlice() {
    try(ColumnVector cv = ColumnVector.fromBoxedInts(10, 12, null, null, 18, 20, 22, 24, 26, 28)) {
      Integer[][] expectedSlice = {
          {12, null},
          {20, 22, 24, 26},
          {null, null},
          {}};

      ColumnVector[] slices = cv.slice(1, 3, 5, 9, 2, 4, 8, 8);

      try {
        for (int i = 0; i < slices.length; i++) {
          final int sliceIndex = i;
          try (HostColumnVector slice = slices[sliceIndex].copyToHost()) {
            assertEquals(expectedSlice[sliceIndex].length, slices[sliceIndex].getRowCount());
            IntStream.range(0, expectedSlice[sliceIndex].length).forEach(rowCount -> {
              if (expectedSlice[sliceIndex][rowCount] == null) {
                assertTrue(slice.isNull(rowCount));
              } else {
                assertEquals(expectedSlice[sliceIndex][rowCount],
                    slice.getInt(rowCount));
              }
            });
          }
        }
        assertEquals(4, slices.length);
      } finally {
        for (int i = 0 ; i < slices.length ; i++) {
          if (slices[i] != null) {
            slices[i].close();
          }
        }
      }
    }
  }

  @Test
  void testStringSlice() {
    try(ColumnVector cv = ColumnVector.fromStrings("foo", "bar", null, null, "baz", "hello", "world", "cuda", "is", "great")) {
      String[][] expectedSlice = {
          {"foo", "bar"},
          {null, null, "baz"},
          {null, "baz", "hello"}};

      ColumnVector[] slices = cv.slice(0, 2, 2, 5, 3, 6);

      try {
        for (int i = 0; i < slices.length; i++) {
          final int sliceIndex = i;
          try (HostColumnVector slice = slices[sliceIndex].copyToHost()) {
            assertEquals(expectedSlice[sliceIndex].length, slices[sliceIndex].getRowCount());
            IntStream.range(0, expectedSlice[sliceIndex].length).forEach(rowCount -> {
              if (expectedSlice[sliceIndex][rowCount] == null) {
                assertTrue(slice.isNull(rowCount));
              } else {
                assertEquals(expectedSlice[sliceIndex][rowCount],
                    slice.getJavaString(rowCount));
              }
            });
          }
        }
        assertEquals(3, slices.length);
      } finally {
        for (int i = 0 ; i < slices.length ; i++) {
          if (slices[i] != null) {
            slices[i].close();
          }
        }
      }
    }
  }

  @Test
  void testSplitWithArray() {
    assumeTrue(Cuda.isEnvCompatibleForTesting());
    try(ColumnVector cv = ColumnVector.fromBoxedInts(10, 12, null, null, 18, 20, 22, 24, 26, 28)) {
      Integer[][] expectedData = {
          {10},
          {12, null},
          {null, 18},
          {20, 22, 24, 26},
          {28}};

      ColumnVector[] splits = cv.split(1, 3, 5, 9);
      try {
        assertEquals(expectedData.length, splits.length);
        for (int splitIndex = 0; splitIndex < splits.length; splitIndex++) {
          try (HostColumnVector subVec = splits[splitIndex].copyToHost()) {
            assertEquals(expectedData[splitIndex].length, subVec.getRowCount());
            for (int subIndex = 0; subIndex < expectedData[splitIndex].length; subIndex++) {
              Integer expected = expectedData[splitIndex][subIndex];
              if (expected == null) {
                assertTrue(subVec.isNull(subIndex));
              } else {
                assertEquals(expected, subVec.getInt(subIndex));
              }
            }
          }
        }
      } finally {
        for (int i = 0 ; i < splits.length ; i++) {
          if (splits[i] != null) {
            splits[i].close();
          }
        }
      }
    }
  }

  @Test
  void testWithOddSlices() {
    try (ColumnVector cv = ColumnVector.fromBoxedInts(10, 12, null, null, 18, 20, 22, 24, 26, 28)) {
      assertThrows(CudfException.class, () -> cv.slice(1, 3, 5, 9, 2, 4, 8));
    }
  }

  @Test
  void testTrimStringsWhiteSpace() {
    try (ColumnVector cv = ColumnVector.fromStrings(" 123", "123 ", null, " 123 ", "\t\t123\n\n");
         ColumnVector trimmed = cv.strip();
         ColumnVector expected = ColumnVector.fromStrings("123", "123", null, "123", "123")) {
      TableTest.assertColumnsAreEqual(expected, trimmed);
    }
  }

  @Test
  void testTrimStrings() {
    try (ColumnVector cv = ColumnVector.fromStrings("123", "123 ", null, "1231", "\t\t123\n\n");
         Scalar one = Scalar.fromString(" 1");
         ColumnVector trimmed = cv.strip(one);
         ColumnVector expected = ColumnVector.fromStrings("23", "23", null, "23", "\t\t123\n\n")) {
      TableTest.assertColumnsAreEqual(expected, trimmed);
    }
  }

  @Test
  void testLeftTrimStringsWhiteSpace() {
    try (ColumnVector cv = ColumnVector.fromStrings(" 123", "123 ", null, " 123 ", "\t\t123\n\n");
         ColumnVector trimmed = cv.lstrip();
         ColumnVector expected = ColumnVector.fromStrings("123", "123 ", null, "123 ", "123\n\n")) {
      TableTest.assertColumnsAreEqual(expected, trimmed);
    }
  }

  @Test
  void testLeftTrimStrings() {
    try (ColumnVector cv = ColumnVector.fromStrings("123", " 123 ", null, "1231", "\t\t123\n\n");
         Scalar one = Scalar.fromString(" 1");
         ColumnVector trimmed = cv.lstrip(one);
         ColumnVector expected = ColumnVector.fromStrings("23", "23 ", null, "231", "\t\t123\n\n")) {
      TableTest.assertColumnsAreEqual(expected, trimmed);
    }
  }

  @Test
  void testRightTrimStringsWhiteSpace() {
    try (ColumnVector cv = ColumnVector.fromStrings(" 123", "123 ", null, " 123 ", "\t\t123\n\n");
         ColumnVector trimmed = cv.rstrip();
         ColumnVector expected = ColumnVector.fromStrings(" 123", "123", null, " 123", "\t\t123")) {
      TableTest.assertColumnsAreEqual(expected, trimmed);
    }
  }

  @Test
  void testRightTrimStrings() {
    try (ColumnVector cv = ColumnVector.fromStrings("123", "123 ", null, "1231 ", "\t\t123\n\n");
         Scalar one = Scalar.fromString(" 1");
         ColumnVector trimmed = cv.rstrip(one);
         ColumnVector expected = ColumnVector.fromStrings("123", "123", null, "123", "\t\t123\n\n")) {
      TableTest.assertColumnsAreEqual(expected, trimmed);
    }
  }

  @Test
  void testTrimStringsThrowsException() {
    assertThrows(CudfException.class, () -> {
      try (ColumnVector cv = ColumnVector.fromStrings("123", "123 ", null, "1231", "\t\t123\n\n");
           Scalar nullStr =  Scalar.fromString(null);
           ColumnVector trimmed = cv.strip(nullStr)) {}
    });
    assertThrows(AssertionError.class, () -> {
      try (ColumnVector cv = ColumnVector.fromStrings("123", "123 ", null, "1231", "\t\t123\n\n");
           Scalar one = Scalar.fromInt(1);
           ColumnVector trimmed = cv.strip(one)) {}
    });
    assertThrows(AssertionError.class, () -> {
      try (ColumnVector cv = ColumnVector.fromStrings("123", "123 ", null, "1231", "\t\t123\n\n");
           ColumnVector result = cv.strip(null)) {}
    });
  }

  @Test
  void testAppendStrings() {
    try (HostColumnVector cv = HostColumnVector.build(10, 0, (b) -> {
      b.append("123456789");
      b.append("1011121314151617181920");
      b.append("");
      b.appendNull();
    })) {
      assertEquals(4, cv.getRowCount());
      assertEquals("123456789", cv.getJavaString(0));
      assertEquals("1011121314151617181920", cv.getJavaString(1));
      assertEquals("", cv.getJavaString(2));
      assertTrue(cv.isNull(3));
    }
  }

  @Test
  void testCountElements() {
    DataType dt = new ListType(true, new BasicType(true, DType.INT32));
    try (ColumnVector cv = ColumnVector.fromLists(dt, Arrays.asList(1),
        Arrays.asList(1, 2), null, Arrays.asList(null, null),
        Arrays.asList(1, 2, 3), Arrays.asList(1, 2, 3, 4));
         ColumnVector lengths = cv.countElements();
         ColumnVector expected = ColumnVector.fromBoxedInts(1, 2, null, 2, 3, 4)) {
      TableTest.assertColumnsAreEqual(expected, lengths);
    }
  }

  @Test
  void testStringLengths() {
    try (ColumnVector cv = ColumnVector.fromStrings("1", "12", null, "123", "1234");
      ColumnVector lengths = cv.getCharLengths();
      ColumnVector expected = ColumnVector.fromBoxedInts(1, 2, null, 3, 4)) {
      TableTest.assertColumnsAreEqual(expected, lengths);
    }
  }

  @Test
  void testGetByteCount() {
    try (ColumnVector cv = ColumnVector.fromStrings("1", "12", "123", null, "1234");
         ColumnVector byteLengthVector = cv.getByteCount();
         ColumnVector expected = ColumnVector.fromBoxedInts(1, 2, 3, null, 4)) {
      TableTest.assertColumnsAreEqual(expected, byteLengthVector);
    }
  }

  @Test
  void testEmptyStringColumnOpts() {
    try (ColumnVector cv = ColumnVector.fromStrings()) {
      try (ColumnVector len = cv.getCharLengths()) {
        assertEquals(0, len.getRowCount());
      }

      try (ColumnVector mask = ColumnVector.fromBoxedBooleans();
           Table input = new Table(cv);
           Table filtered = input.filter(mask)) {
        assertEquals(0, filtered.getColumn(0).getRowCount());
      }

      try (ColumnVector len = cv.getByteCount()) {
        assertEquals(0, len.getRowCount());
      }

      try (ColumnVector lower = cv.lower();
           ColumnVector upper = cv.upper()) {
        assertColumnsAreEqual(cv, lower);
        assertColumnsAreEqual(cv, upper);
      }
    }
  }

  @Test
  void testStringManipulation() {
    try (ColumnVector v = ColumnVector.fromStrings("a", "B", "cd", "\u0480\u0481", "E\tf",
                                                   "g\nH", "IJ\"\u0100\u0101\u0500\u0501",
                                                   "kl m", "Nop1", "\\qRs2", "3tuV\'",
                                                   "wX4Yz", "\ud720\ud721");
         ColumnVector e_lower = ColumnVector.fromStrings("a", "b", "cd", "\u0481\u0481", "e\tf",
                                                         "g\nh", "ij\"\u0101\u0101\u0501\u0501",
                                                         "kl m", "nop1", "\\qrs2", "3tuv\'",
                                                         "wx4yz", "\ud720\ud721");
         ColumnVector e_upper = ColumnVector.fromStrings("A", "B", "CD", "\u0480\u0480", "E\tF",
                                                         "G\nH", "IJ\"\u0100\u0100\u0500\u0500",
                                                         "KL M", "NOP1", "\\QRS2", "3TUV\'",
                                                         "WX4YZ", "\ud720\ud721");
         ColumnVector lower = v.lower();
         ColumnVector upper = v.upper()) {
      assertColumnsAreEqual(lower, e_lower);
      assertColumnsAreEqual(upper, e_upper);
    }
    assertThrows(AssertionError.class, () -> {
      try (ColumnVector cv = ColumnVector.fromInts(1, 2, 3, 4);
           ColumnVector lower = cv.lower()) {}
    });
    assertThrows(AssertionError.class, () -> {
      try (ColumnVector cv = ColumnVector.fromInts(1, 2, 3, 4);
           ColumnVector upper = cv.upper()) {}
    });
  }

  @Test
  void testStringManipulationWithNulls() {
    // Special characters in order of usage, capital and small cyrillic koppa
    // Latin A with macron, and cyrillic komi de
    // \ud720 and \ud721 are UTF-8 characters without corresponding upper and lower characters
    try (ColumnVector v = ColumnVector.fromStrings("a", "B", "cd", "\u0480\u0481", "E\tf",
                                                   "g\nH", "IJ\"\u0100\u0101\u0500\u0501",
                                                   "kl m", "Nop1", "\\qRs2", null,
                                                   "3tuV\'", "wX4Yz", "\ud720\ud721");
         ColumnVector e_lower = ColumnVector.fromStrings("a", "b", "cd", "\u0481\u0481", "e\tf",
                                                         "g\nh", "ij\"\u0101\u0101\u0501\u0501",
                                                         "kl m", "nop1", "\\qrs2", null,
                                                         "3tuv\'", "wx4yz", "\ud720\ud721");
         ColumnVector e_upper = ColumnVector.fromStrings("A", "B", "CD", "\u0480\u0480", "E\tF",
                                                         "G\nH", "IJ\"\u0100\u0100\u0500\u0500",
                                                         "KL M", "NOP1", "\\QRS2", null,
                                                         "3TUV\'", "WX4YZ", "\ud720\ud721");
         ColumnVector lower = v.lower();
         ColumnVector upper = v.upper();) {
      assertColumnsAreEqual(lower, e_lower);
      assertColumnsAreEqual(upper, e_upper);
    }
  }

  @Test
  void testStringConcat() {
    try (ColumnVector v = ColumnVector.fromStrings("a", "B", "cd", "\u0480\u0481", "E\tf",
        "g\nH", "IJ\"\u0100\u0101\u0500\u0501",
        "kl m", "Nop1", "\\qRs2", "3tuV\'",
        "wX4Yz", "\ud720\ud721");
         ColumnVector e_concat = ColumnVector.fromStrings("aa", "BB", "cdcd",
             "\u0480\u0481\u0480\u0481", "E\tfE\tf", "g\nHg\nH",
             "IJ\"\u0100\u0101\u0500\u0501IJ\"\u0100\u0101\u0500\u0501",
             "kl mkl m", "Nop1Nop1", "\\qRs2\\qRs2", "3tuV\'3tuV\'",
             "wX4YzwX4Yz", "\ud720\ud721\ud720\ud721");
         Scalar emptyString = Scalar.fromString("");
         ColumnVector concat = ColumnVector.stringConcatenate(emptyString, emptyString, new ColumnView[]{v, v})) {
      assertColumnsAreEqual(concat, e_concat);
    }
    assertThrows(CudfException.class, () -> {
      try (ColumnVector sv = ColumnVector.fromStrings("B", "cd", "\u0480\u0481", "E\tf");
           ColumnVector cv = ColumnVector.fromInts(1, 2, 3, 4);
           Scalar emptyString = Scalar.fromString("");
           ColumnVector concat = ColumnVector.stringConcatenate(emptyString, emptyString, new ColumnView[]{sv, cv})) {
      }
    });
    assertThrows(CudfException.class, () -> {
      try (ColumnVector sv1 = ColumnVector.fromStrings("a", "B", "cd");
           ColumnVector sv2 = ColumnVector.fromStrings("a", "B");
           Scalar emptyString = Scalar.fromString("");
           ColumnVector concat = ColumnVector.stringConcatenate(emptyString, emptyString,
               new ColumnVector[]{sv1, sv2})) {
      }
    });
    assertThrows(AssertionError.class, () -> {
      try (Scalar emptyString = Scalar.fromString("");
           ColumnVector concat = ColumnVector.stringConcatenate(emptyString, emptyString, new ColumnView[]{})) {
      }
    });
    assertThrows(CudfException.class, () -> {
      try (ColumnVector sv = ColumnVector.fromStrings("a", "B", "cd");
           Scalar emptyString = Scalar.fromString("");
           Scalar nullString = Scalar.fromString(null);
           ColumnVector concat = ColumnVector.stringConcatenate(nullString, emptyString, new ColumnView[]{sv, sv})) {
      }
    });
    assertThrows(AssertionError.class, () -> {
      try (ColumnVector sv = ColumnVector.fromStrings("a", "B", "cd");
           Scalar emptyString = Scalar.fromString("");
           ColumnVector concat = ColumnVector.stringConcatenate(null, emptyString, new ColumnView[]{sv, sv})) {
      }
    });
    assertThrows(AssertionError.class, () -> {
      try (ColumnVector sv = ColumnVector.fromStrings("a", "B", "cd");
           Scalar emptyString = Scalar.fromString("");
           ColumnVector concat = ColumnVector.stringConcatenate(emptyString, null, new ColumnView[]{sv, sv})) {
      }
    });
    assertThrows(AssertionError.class, () -> {
      try (ColumnVector sv = ColumnVector.fromStrings("a", "B", "cd");
           Scalar emptyString = Scalar.fromString("");
           ColumnVector concat = ColumnVector.stringConcatenate(emptyString, emptyString, new ColumnView[]{sv, null})) {
      }
    });
  }

  @Test
  void testStringConcatWithNulls() {
    try (ColumnVector v = ColumnVector.fromStrings("a", "B", "cd", "\u0480\u0481", "E\tf",
        "g\nH", "IJ\"\u0100\u0101\u0500\u0501",
        "kl m", "Nop1", "\\qRs2", null,
        "3tuV\'", "wX4Yz", "\ud720\ud721");
         ColumnVector e_concat = ColumnVector.fromStrings("aa", "BB", "cdcd",
             "\u0480\u0481\u0480\u0481", "E\tfE\tf", "g\nHg\nH",
             "IJ\"\u0100\u0101\u0500\u0501IJ\"\u0100\u0101\u0500\u0501",
             "kl mkl m", "Nop1Nop1", "\\qRs2\\qRs2", "NULLNULL",
             "3tuV\'3tuV\'", "wX4YzwX4Yz", "\ud720\ud721\ud720\ud721");
         Scalar emptyString = Scalar.fromString("");
         Scalar nullSubstitute = Scalar.fromString("NULL");
         ColumnVector concat = ColumnVector.stringConcatenate(emptyString, nullSubstitute, new ColumnView[]{v, v})) {
      assertColumnsAreEqual(concat, e_concat);
    }

    assertThrows(CudfException.class, () -> {
      try (ColumnVector v = ColumnVector.fromStrings("a", "B", "cd", "\u0480\u0481", "E\tf",
          "g\nH", "IJ\"\u0100\u0101\u0500\u0501",
          "kl m", "Nop1", "\\qRs2", null,
          "3tuV\'", "wX4Yz", "\ud720\ud721");
           Scalar emptyString = Scalar.fromString("");
           Scalar nullSubstitute = Scalar.fromString("NULL");
           ColumnVector concat = ColumnVector.stringConcatenate(emptyString, nullSubstitute, new ColumnView[]{v})) {
      }
    });
  }

  @Test
  void testStringConcatSeparators() {
    try (ColumnVector sv1 = ColumnVector.fromStrings("a", "B", "cd", "\u0480\u0481", "E\tf", null, null, "\\G\u0100");
         ColumnVector sv2 = ColumnVector.fromStrings("b", "C", "\u0500\u0501", "x\nYz", null, null, "", null);
         ColumnVector e_concat = ColumnVector.fromStrings("aA1\t\ud721b", "BA1\t\ud721C", "cdA1\t\ud721\u0500\u0501",
             "\u0480\u0481A1\t\ud721x\nYz", null, null, null, null);
         Scalar separatorString = Scalar.fromString("A1\t\ud721");
         Scalar nullString = Scalar.fromString(null);
         ColumnVector concat = ColumnVector.stringConcatenate(separatorString, nullString, new ColumnView[]{sv1, sv2})) {
      assertColumnsAreEqual(concat, e_concat);
    }
  }

  @Test
  void testStringConcatSeparatorsEmptyStringForNull() {
    try (ColumnVector sv1 = ColumnVector.fromStrings("a", "B", "cd", "\u0480\u0481", "E\tf", null, null, "\\G\u0100");
         ColumnVector sv2 = ColumnVector.fromStrings("b", "C", "\u0500\u0501", "x\nYz", null, null, "", null);
         ColumnVector e_concat = ColumnVector.fromStrings("aA1\t\ud721b", "BA1\t\ud721C", "cdA1\t\ud721\u0500\u0501",
             "\u0480\u0481A1\t\ud721x\nYz", "E\tf", "", "", "\\G\u0100");
         Scalar separatorString = Scalar.fromString("A1\t\ud721");
         Scalar narep = Scalar.fromString("");
         ColumnVector concat = ColumnVector.stringConcatenate(separatorString, narep, new ColumnView[]{sv1, sv2}, false)) {
      assertColumnsAreEqual(concat, e_concat);
    }
  }

  @Test
  void testConcatWsTypeError() {
    try (ColumnVector v0 = ColumnVector.fromInts(1, 2, 3, 4);
         ColumnVector v1 = ColumnVector.fromFloats(5.0f, 6.0f);
         ColumnVector sep_col = ColumnVector.fromStrings("-*");
         Scalar separatorString = Scalar.fromString(null);
         Scalar nullString = Scalar.fromString(null)) {
      assertThrows(CudfException.class, () -> ColumnVector.stringConcatenate(
          new ColumnView[]{v0, v1}, sep_col, separatorString, nullString, false));
    }
  }

  @Test
  void testConcatWsNoColumn() {
    try (ColumnVector sep_col = ColumnVector.fromStrings("-*");
         Scalar separatorString = Scalar.fromString(null);
         Scalar nullString = Scalar.fromString(null)) {
      assertThrows(AssertionError.class, () -> ColumnVector.stringConcatenate(
          new ColumnView[]{}, sep_col, separatorString, nullString, false));
    }
  }

  @Test
  void testStringConcatWsSimple() {
    try (ColumnVector sv1 = ColumnVector.fromStrings("a");
         ColumnVector sv2 = ColumnVector.fromStrings("B");
         ColumnVector sv3 = ColumnVector.fromStrings("cd");
         ColumnVector sv4 = ColumnVector.fromStrings("\u0480\u0481");
         ColumnVector sv5 = ColumnVector.fromStrings("E\tf");
         ColumnVector sv6 = ColumnVector.fromStrings("M");
         ColumnVector sv7 = ColumnVector.fromStrings("\\G\u0100");
         ColumnVector sep_col = ColumnVector.fromStrings("-*");
         ColumnVector e_concat = ColumnVector.fromStrings("a-*B-*cd-*\u0480\u0481-*E\tf-*M-*\\G\u0100");
         Scalar separatorString = Scalar.fromString(null);
         Scalar col_narep = Scalar.fromString("");
         ColumnVector concat = ColumnVector.stringConcatenate(
             new ColumnView[]{sv1, sv2, sv3, sv4, sv5, sv6, sv7}, sep_col, separatorString,
             col_narep, false)) {
      assertColumnsAreEqual(e_concat, concat);
    }
  }

  @Test
  void testStringConcatWsSimpleOtherApi() {
    try (ColumnVector sv1 = ColumnVector.fromStrings("a");
         ColumnVector sv2 = ColumnVector.fromStrings("B");
         ColumnVector sv3 = ColumnVector.fromStrings("cd");
         ColumnVector sv4 = ColumnVector.fromStrings("\u0480\u0481");
         ColumnVector sv5 = ColumnVector.fromStrings("E\tf");
         ColumnVector sv6 = ColumnVector.fromStrings("M");
         ColumnVector sv7 = ColumnVector.fromStrings("\\G\u0100");
         ColumnVector sep_col = ColumnVector.fromStrings("-*");
         ColumnVector e_concat = ColumnVector.fromStrings("a-*B-*cd-*\u0480\u0481-*E\tf-*M-*\\G\u0100");
         Scalar separatorString = Scalar.fromString(null);
         Scalar col_narep = Scalar.fromString("");
         ColumnVector concat = ColumnVector.stringConcatenate(
             new ColumnView[]{sv1, sv2, sv3, sv4, sv5, sv6, sv7}, sep_col)) {
      assertColumnsAreEqual(e_concat, concat);
    }
  }

  @Test
  void testStringConcatWsOneCol() {
    try (ColumnVector sv1 = ColumnVector.fromStrings("a");
         ColumnVector sep_col = ColumnVector.fromStrings("-*");
         ColumnVector e_concat = ColumnVector.fromStrings("a");
         Scalar separatorString = Scalar.fromString(null);
         Scalar col_narep = Scalar.fromString("");
         ColumnVector concat = ColumnVector.stringConcatenate(
             new ColumnView[]{sv1}, sep_col, separatorString,
             col_narep, false)) {
      assertColumnsAreEqual(e_concat, concat);
    }
  }

  @Test
  void testStringConcatWsNullSep() {
    try (ColumnVector sv1 = ColumnVector.fromStrings("a", "c");
         ColumnVector sv2 = ColumnVector.fromStrings("b", "d");
         Scalar nullString = Scalar.fromString(null);
         ColumnVector sep_col = ColumnVector.fromScalar(nullString, 2);
         ColumnVector e_concat = ColumnVector.fromScalar(nullString, 2);
         Scalar separatorString = Scalar.fromString(null);
         Scalar col_narep = Scalar.fromString("");
         ColumnVector concat = ColumnVector.stringConcatenate(new ColumnView[]{sv1, sv2},
             sep_col, separatorString, col_narep, false)) {
      assertColumnsAreEqual(e_concat, concat);
    }
  }

  @Test
  void testStringConcatWsNullValueInCol() {
    try (ColumnVector sv1 = ColumnVector.fromStrings("a", "c", null);
         ColumnVector sv2 = ColumnVector.fromStrings("b", "", "e");
         ColumnVector sep_col = ColumnVector.fromStrings("-", "-", "-");
         ColumnVector e_concat = ColumnVector.fromStrings("a-b", "c-", "e");
         Scalar separatorString = Scalar.fromString(null);
         Scalar col_narep = Scalar.fromString("");
         ColumnVector concat = ColumnVector.stringConcatenate(new ColumnView[]{sv1, sv2},
             sep_col, separatorString, col_narep, false)) {
      assertColumnsAreEqual(e_concat, concat);
    }
  }

  @Test
  void testStringConcatWsNullValueInColKeepNull() {
    try (ColumnVector sv1 = ColumnVector.fromStrings("a", "c", null);
         ColumnVector sv2 = ColumnVector.fromStrings("b", "", "e");
         ColumnVector sep_col = ColumnVector.fromStrings("-", "-", "-");
         ColumnVector e_concat = ColumnVector.fromStrings("a-b", "c-", null);
         Scalar separatorString = Scalar.fromString(null);
         Scalar col_narep = Scalar.fromString(null);
         ColumnVector concat = ColumnVector.stringConcatenate(new ColumnView[]{sv1, sv2},
             sep_col, separatorString, col_narep, true)) {
      assertColumnsAreEqual(e_concat, concat);
    }
  }

  @Test
  void testStringConcatWsNullValueInColSepTrue() {
    try (ColumnVector sv1 = ColumnVector.fromStrings("a", "c", null);
         ColumnVector sv2 = ColumnVector.fromStrings("b", "", "e");
         ColumnVector sep_col = ColumnVector.fromStrings("-", "-", "-");
         // this is failing?
         ColumnVector e_concat = ColumnVector.fromStrings("a-b", "c-", "-e");
         Scalar separatorString = Scalar.fromString(null);
         Scalar col_narep = Scalar.fromString("");
         ColumnVector concat = ColumnVector.stringConcatenate(new ColumnView[]{sv1, sv2},
             sep_col, separatorString, col_narep, true)) {
      assertColumnsAreEqual(e_concat, concat);
    }
  }

  @Test
  void testStringConcatWsSingleCol() {
    try (ColumnVector sv1 = ColumnVector.fromStrings("a", "c", "e");
         ColumnVector sep_col = ColumnVector.fromStrings("-", "-", "-");
         ColumnVector e_concat = ColumnVector.fromStrings("a", "c", "e");
         Scalar separatorString = Scalar.fromString(null);
         Scalar col_narep = Scalar.fromString("");
         ColumnVector concat = ColumnVector.stringConcatenate(new ColumnView[]{sv1},
             sep_col, separatorString, col_narep, false)) {
      assertColumnsAreEqual(e_concat, concat);
    }
  }

  @Test
  void testStringConcatWsNullAllCol() {
    try (Scalar nullString = Scalar.fromString(null);
         ColumnVector sv1 = ColumnVector.fromScalar(nullString, 3);
         ColumnVector sv2 = ColumnVector.fromScalar(nullString, 3);
         ColumnVector sep_col = ColumnVector.fromStrings("-", "-", "-");
         ColumnVector e_concat = ColumnVector.fromStrings("", "", "");
         Scalar separatorString = Scalar.fromString(null);
         Scalar col_narep = Scalar.fromString("");
         ColumnVector concat = ColumnVector.stringConcatenate(new ColumnView[]{sv1, sv2},
             sep_col, separatorString, col_narep, false)) {
      assertColumnsAreEqual(e_concat, concat);
    }
  }

  @Test
  void testStringConcatWsNullAllColSepTrue() {
    try (Scalar nullString = Scalar.fromString(null);
         ColumnVector sv1 = ColumnVector.fromScalar(nullString, 3);
         ColumnVector sv2 = ColumnVector.fromScalar(nullString, 3);
         ColumnVector sep_col = ColumnVector.fromStrings("-", "-", "-");
         ColumnVector e_concat = ColumnVector.fromStrings("-", "-", "-");
         Scalar separatorString = Scalar.fromString(null);
         Scalar col_narep = Scalar.fromString("");
         ColumnVector concat = ColumnVector.stringConcatenate(new ColumnView[]{sv1, sv2},
             sep_col, separatorString, col_narep, true)) {
      assertColumnsAreEqual(e_concat, concat);
    }
  }

  @Test
  void testStringConcatWsSingleListCol() {
    try (ColumnVector cv1 = ColumnVector.fromLists(new HostColumnVector.ListType(true,
           new HostColumnVector.BasicType(true, DType.STRING)),
           Arrays.asList("aaa"), Arrays.asList("b", "c", "d"),
           Arrays.asList("\u0480\u0481", null, "asdfbe", null));
         ColumnVector sep_col = ColumnVector.fromStrings("-", "-", "*");
         ColumnVector e_concat = ColumnVector.fromStrings("aaa", "b-c-d", "\u0480\u0481*asdfbe");
         Scalar separatorString = Scalar.fromString(null);
         Scalar col_narep = Scalar.fromString("");
         ColumnVector concat = cv1.stringConcatenateListElements(sep_col, separatorString,
             col_narep, false, false)) {
      assertColumnsAreEqual(e_concat, concat);
    }
  }

  @Test
  void testStringConcatWsSingleListColDefaultApi() {
    try (ColumnVector cv1 = ColumnVector.fromLists(new HostColumnVector.ListType(true,
           new HostColumnVector.BasicType(true, DType.STRING)),
           Arrays.asList("aaa"), Arrays.asList("b", "c", "d"),
           Arrays.asList("\u0480\u0481", null, "asdfbe", null));
         ColumnVector sep_col = ColumnVector.fromStrings("-", "-", "*");
         ColumnVector e_concat = ColumnVector.fromStrings("aaa", "b-c-d", "\u0480\u0481*asdfbe");
         ColumnVector concat = cv1.stringConcatenateListElements(sep_col)) {
      assertColumnsAreEqual(e_concat, concat);
    }
  }

  @Test
  void testStringConcatWsSingleListColScalarSep() {
    try (ColumnVector cv1 = ColumnVector.fromLists(new HostColumnVector.ListType(true,
           new HostColumnVector.BasicType(true, DType.STRING)),
           Arrays.asList("aaa"), Arrays.asList("b", "c", "d"),
           Arrays.asList("\u0480\u0481", null, "asdfbe", null));
         Scalar separatorString = Scalar.fromString("-");
         ColumnVector e_concat = ColumnVector.fromStrings("aaa", "b-c-d", "\u0480\u0481-asdfbe");
         Scalar narep = Scalar.fromString("");
         ColumnVector concat = cv1.stringConcatenateListElements(separatorString, narep, false,
             false)) {
      assertColumnsAreEqual(e_concat, concat);
    }
  }

  @Test
  void testStringConcatWsSingleListColAllNulls() {
    try (ColumnVector cv1 = ColumnVector.fromLists(new HostColumnVector.ListType(true,
           new HostColumnVector.BasicType(true, DType.STRING)),
           Arrays.asList("aaa"), Arrays.asList(null, null, null));
         ColumnVector sep_col = ColumnVector.fromStrings("-", "-");
         ColumnVector e_concat = ColumnVector.fromStrings("aaa", null);
         Scalar separatorString = Scalar.fromString(null);
         Scalar col_narep = Scalar.fromString("");
         ColumnVector concat = cv1.stringConcatenateListElements(sep_col, separatorString,
             col_narep, false, false)) {
      assertColumnsAreEqual(e_concat, concat);
    }
  }

  @Test
  void testStringConcatWsSingleListColAllNullsScalarSep() {
    try (ColumnVector cv1 = ColumnVector.fromLists(new HostColumnVector.ListType(true,
           new HostColumnVector.BasicType(true, DType.STRING)),
           Arrays.asList("aaa"), Arrays.asList(null, null, null));
         ColumnVector e_concat = ColumnVector.fromStrings("aaa", null);
         Scalar separatorString = Scalar.fromString("-");
         Scalar narep = Scalar.fromString("");
         ColumnVector concat = cv1.stringConcatenateListElements(separatorString, narep,
             false, false)) {
      assertColumnsAreEqual(e_concat, concat);
    }
  }

  @Test
  void testStringConcatWsSingleListColAllNullsSepTrue() {
    try (ColumnVector cv1 = ColumnVector.fromLists(new HostColumnVector.ListType(true,
           new HostColumnVector.BasicType(true, DType.STRING)),
           Arrays.asList("aaa"), Arrays.asList(null, null, null));
         ColumnVector sep_col = ColumnVector.fromStrings("-", "-");
         ColumnVector e_concat = ColumnVector.fromStrings("aaa", null);
         Scalar separatorString = Scalar.fromString(null);
         Scalar col_narep = Scalar.fromString("");
         ColumnVector concat = cv1.stringConcatenateListElements(sep_col, separatorString,
             col_narep, true, false)) {
      assertColumnsAreEqual(e_concat, concat);
    }
  }

  @Test
  void testStringConcatWsSingleListColAllNullsKeepNulls() {
    try (ColumnVector cv1 = ColumnVector.fromLists(new HostColumnVector.ListType(true,
           new HostColumnVector.BasicType(true, DType.STRING)),
           Arrays.asList("aaa"), Arrays.asList(null, null, null));
         ColumnVector sep_col = ColumnVector.fromStrings("-", "-");
         ColumnVector e_concat = ColumnVector.fromStrings("aaa", null);
         Scalar separatorString = Scalar.fromString(null);
         Scalar col_narep = Scalar.fromString(null);
         ColumnVector concat = cv1.stringConcatenateListElements(sep_col, separatorString,
             col_narep, true, false)) {
      assertColumnsAreEqual(e_concat, concat);
    }
  }

  @Test
  void testStringConcatWsSingleListColEmptyArray() {
    try (ColumnVector cv1 = ColumnVector.fromLists(new HostColumnVector.ListType(true,
           new HostColumnVector.BasicType(true, DType.STRING)),
           Arrays.asList("aaa", "bbbb"), Arrays.asList());
         ColumnVector sep_col = ColumnVector.fromStrings("-", "-");
         ColumnVector e_concat = ColumnVector.fromStrings("aaa-bbbb", null);
         Scalar separatorString = Scalar.fromString(null);
         Scalar col_narep = Scalar.fromString("");
         // set the parameter to return null on empty array
         ColumnVector concat = cv1.stringConcatenateListElements(sep_col, separatorString,
             col_narep, false, false)) {
      assertColumnsAreEqual(e_concat, concat);
    }
  }

  @Test
  void testStringConcatWsSingleListColEmptyArrayReturnEmpty() {
    try (ColumnVector cv1 = ColumnVector.fromLists(new HostColumnVector.ListType(true,
           new HostColumnVector.BasicType(true, DType.STRING)),
           Arrays.asList("aaa", "bbbb"), Arrays.asList());
         ColumnVector sep_col = ColumnVector.fromStrings("-", "-");
         ColumnVector e_concat = ColumnVector.fromStrings("aaa-bbbb", "");
         Scalar separatorString = Scalar.fromString(null);
         Scalar col_narep = Scalar.fromString("");
         // set the parameter to return empty string on empty array
         ColumnVector concat = cv1.stringConcatenateListElements(sep_col, separatorString,
             col_narep, false, true)) {
      assertColumnsAreEqual(e_concat, concat);
    }
  }

  @Test
  void testRepeatStrings() {
     // Empty strings column.
    try (ColumnVector sv = ColumnVector.fromStrings("", "", "");
         ColumnVector result = sv.repeatStrings(1)) {
      assertColumnsAreEqual(sv, result);
    }

    // Zero repeatTimes.
    try (ColumnVector sv = ColumnVector.fromStrings("abc", "xyz", "123");
         ColumnVector result = sv.repeatStrings(0);
         ColumnVector expected = ColumnVector.fromStrings("", "", "")) {
      assertColumnsAreEqual(expected, result);
    }

    // Negative repeatTimes.
    try (ColumnVector sv = ColumnVector.fromStrings("abc", "xyz", "123");
         ColumnVector result = sv.repeatStrings(-1);
         ColumnVector expected = ColumnVector.fromStrings("", "", "")) {
      assertColumnsAreEqual(expected, result);
    }

    // Strings column containing both null and empty, output is copied exactly from input.
    try (ColumnVector sv = ColumnVector.fromStrings("abc", "", null, "123", null);
         ColumnVector result = sv.repeatStrings(1)) {
      assertColumnsAreEqual(sv, result);
    }

    // Strings column containing both null and empty.
    try (ColumnVector sv = ColumnVector.fromStrings("abc", "", null, "123", null);
         ColumnVector result = sv.repeatStrings( 2);
         ColumnVector expected = ColumnVector.fromStrings("abcabc", "", null, "123123", null)) {
      assertColumnsAreEqual(expected, result);
    }
  }

  @Test
  void testListConcatByRow() {
    try (ColumnVector cv = ColumnVector.fromLists(new HostColumnVector.ListType(true,
            new HostColumnVector.BasicType(true, DType.INT32)),
        Arrays.asList(0), Arrays.asList(1, 2, 3), null, Arrays.asList(), Arrays.asList());
         ColumnVector result = ColumnVector.listConcatenateByRow(cv)) {
      assertColumnsAreEqual(cv, result);
    }

    try (ColumnVector cv1 = ColumnVector.fromLists(new HostColumnVector.ListType(true,
            new HostColumnVector.BasicType(true, DType.INT32)),
        Arrays.asList(0), Arrays.asList(1, 2, 3), null, Arrays.asList(), Arrays.asList());
         ColumnVector cv2 = ColumnVector.fromLists(new HostColumnVector.ListType(true,
                 new HostColumnVector.BasicType(true, DType.INT32)),
             Arrays.asList(1, 2, 3), Arrays.asList((Integer) null), Arrays.asList(10, 12), Arrays.asList(100, 200, 300),
             Arrays.asList());
         ColumnVector result = ColumnVector.listConcatenateByRow(cv1, cv2);
         ColumnVector expect = ColumnVector.fromLists(new HostColumnVector.ListType(true,
                 new HostColumnVector.BasicType(true, DType.INT32)),
             Arrays.asList(0, 1, 2, 3), Arrays.asList(1, 2, 3, null), null, Arrays.asList(100, 200, 300),
             Arrays.asList())) {
      assertColumnsAreEqual(expect, result);
    }

    try (ColumnVector cv1 = ColumnVector.fromLists(new HostColumnVector.ListType(true,
            new HostColumnVector.BasicType(true, DType.STRING)),
        Arrays.asList("AAA", "BBB"), Arrays.asList("aaa"), Arrays.asList("111"), Arrays.asList("X"),
        Arrays.asList());
         ColumnVector cv2 = ColumnVector.fromLists(new HostColumnVector.ListType(true,
                 new HostColumnVector.BasicType(true, DType.STRING)),
             Arrays.asList(), Arrays.asList("bbb", "ccc"), null, Arrays.asList((String) null),
             Arrays.asList());
         ColumnVector cv3 = ColumnVector.fromLists(new HostColumnVector.ListType(true,
                 new HostColumnVector.BasicType(true, DType.STRING)),
             Arrays.asList("CCC"), Arrays.asList(), Arrays.asList("222", "333"), Arrays.asList("Z"),
             Arrays.asList());
         ColumnVector result = ColumnVector.listConcatenateByRow(cv1, cv2, cv3);
         ColumnVector expect = ColumnVector.fromLists(new HostColumnVector.ListType(true,
                 new HostColumnVector.BasicType(true, DType.STRING)),
             Arrays.asList("AAA", "BBB", "CCC"), Arrays.asList("aaa", "bbb", "ccc"), null,
             Arrays.asList("X", null, "Z"), Arrays.asList())) {
      assertColumnsAreEqual(expect, result);
    }

    try (ColumnVector cv = ColumnVector.fromLists(new HostColumnVector.ListType(true,
            new HostColumnVector.BasicType(true, DType.FLOAT64)),
        Arrays.asList(1.23, 0.0, Double.NaN), Arrays.asList(), null, Arrays.asList(-1.23e10, null));
         ColumnVector result = ColumnVector.listConcatenateByRow(cv, cv, cv);
         ColumnVector expect = ColumnVector.fromLists(new HostColumnVector.ListType(true,
                 new HostColumnVector.BasicType(true, DType.FLOAT64)),
             Arrays.asList(1.23, 0.0, Double.NaN, 1.23, 0.0, Double.NaN, 1.23, 0.0, Double.NaN),
             Arrays.asList(), null, Arrays.asList(-1.23e10, null, -1.23e10, null, -1.23e10, null))) {
      assertColumnsAreEqual(expect, result);
    }

    assertThrows(CudfException.class, () -> {
      try (ColumnVector cv = ColumnVector.fromInts(1, 2, 3);
           ColumnVector result = ColumnVector.listConcatenateByRow(cv, cv)) {
      }
    });

    assertThrows(CudfException.class, () -> {
      try (ColumnVector cv = ColumnVector.fromLists(new HostColumnVector.ListType(true,
          new HostColumnVector.ListType(true,
              new HostColumnVector.BasicType(true, DType.INT32))), Arrays.asList(Arrays.asList(1)));
           ColumnVector result = ColumnVector.listConcatenateByRow(cv, cv)) {
      }
    });

    assertThrows(CudfException.class, () -> {
      try (ColumnVector cv1 = ColumnVector.fromLists(new HostColumnVector.ListType(true,
          new HostColumnVector.BasicType(true, DType.INT32)), Arrays.asList(1, 2, 3));
           ColumnVector cv2 = ColumnVector.fromLists(new HostColumnVector.ListType(true,
               new HostColumnVector.BasicType(true, DType.INT32)), Arrays.asList(1, 2), Arrays.asList(3));
           ColumnVector result = ColumnVector.listConcatenateByRow(cv1, cv2)) {
      }
    });

    assertThrows(CudfException.class, () -> {
      try (ColumnVector cv1 = ColumnVector.fromLists(new HostColumnVector.ListType(true,
          new HostColumnVector.BasicType(true, DType.INT32)), Arrays.asList(1, 2, 3));
           ColumnVector cv2 = ColumnVector.fromLists(new HostColumnVector.ListType(true,
               new HostColumnVector.BasicType(true, DType.INT64)), Arrays.asList(1L));
           ColumnVector result = ColumnVector.listConcatenateByRow(cv1, cv2)) {
      }
    });
  }

  @Test
  void testListConcatByRowIgnoreNull() {
    try (ColumnVector cv = ColumnVector.fromLists(new HostColumnVector.ListType(true,
            new HostColumnVector.BasicType(true, DType.INT32)),
        Arrays.asList(0), Arrays.asList(1, 2, 3), null, Arrays.asList(), Arrays.asList());
         ColumnVector result = ColumnVector.listConcatenateByRow(true, cv)) {
      assertColumnsAreEqual(cv, result);
    }

    try (ColumnVector cv1 = ColumnVector.fromLists(new HostColumnVector.ListType(true,
            new HostColumnVector.BasicType(true, DType.INT32)),
        Arrays.asList((Integer) null), Arrays.asList(1, 2, 3), null, Arrays.asList(), null);
         ColumnVector cv2 = ColumnVector.fromLists(new HostColumnVector.ListType(true,
                 new HostColumnVector.BasicType(true, DType.INT32)),
             Arrays.asList(1, 2, 3), null, Arrays.asList(10, 12), Arrays.asList(100, 200, 300), null);
         ColumnVector result = ColumnVector.listConcatenateByRow(true, cv1, cv2);
         ColumnVector expect = ColumnVector.fromLists(new HostColumnVector.ListType(true,
                 new HostColumnVector.BasicType(true, DType.INT32)),
             Arrays.asList(null, 1, 2, 3), Arrays.asList(1, 2, 3), Arrays.asList(10, 12),
             Arrays.asList(100, 200, 300), null)) {
      assertColumnsAreEqual(expect, result);
    }

    try (ColumnVector cv1 = ColumnVector.fromLists(new HostColumnVector.ListType(true,
            new HostColumnVector.BasicType(true, DType.STRING)),
        Arrays.asList("AAA", "BBB"), Arrays.asList("aaa"), Arrays.asList("111"), null, null);
         ColumnVector cv2 = ColumnVector.fromLists(new HostColumnVector.ListType(true,
                 new HostColumnVector.BasicType(true, DType.STRING)),
             null, Arrays.asList("bbb", "ccc"), null, Arrays.asList("Y", null), null);
         ColumnVector cv3 = ColumnVector.fromLists(new HostColumnVector.ListType(true,
                 new HostColumnVector.BasicType(true, DType.STRING)),
             Arrays.asList("CCC"), Arrays.asList(), Arrays.asList("222", "333"), null, null);
         ColumnVector result = ColumnVector.listConcatenateByRow(true, cv1, cv2, cv3);
         ColumnVector expect = ColumnVector.fromLists(new HostColumnVector.ListType(true,
                 new HostColumnVector.BasicType(true, DType.STRING)),
             Arrays.asList("AAA", "BBB", "CCC"), Arrays.asList("aaa", "bbb", "ccc"),
             Arrays.asList("111", "222", "333"), Arrays.asList("Y", null), null)) {
      assertColumnsAreEqual(expect, result);
    }

    try (ColumnVector cv = ColumnVector.fromLists(new HostColumnVector.ListType(true,
            new HostColumnVector.BasicType(true, DType.FLOAT64)),
        Arrays.asList(1.23, 0.0, Double.NaN), Arrays.asList(), null, Arrays.asList(-1.23e10, null));
         ColumnVector result = ColumnVector.listConcatenateByRow(true, cv, cv, cv);
         ColumnVector expect = ColumnVector.fromLists(new HostColumnVector.ListType(true,
                 new HostColumnVector.BasicType(true, DType.FLOAT64)),
             Arrays.asList(1.23, 0.0, Double.NaN, 1.23, 0.0, Double.NaN, 1.23, 0.0, Double.NaN),
             Arrays.asList(), null, Arrays.asList(-1.23e10, null, -1.23e10, null, -1.23e10, null))) {
      assertColumnsAreEqual(expect, result);
    }
  }

  @Test
  void testPrefixSum() {
    try (ColumnVector v1 = ColumnVector.fromLongs(1, 2, 3, 5, 8, 10);
         ColumnVector summed = v1.prefixSum();
         ColumnVector expected = ColumnVector.fromLongs(1, 3, 6, 11, 19, 29)) {
      assertColumnsAreEqual(expected, summed);
    }
  }

  @Test
  void testScanSum() {
    try (ColumnVector v1 = ColumnVector.fromBoxedInts(1, 2, null, 3, 5, 8, 10)) {
      // Due to https://github.com/rapidsai/cudf/issues/8462 NullPolicy.INCLUDE
      // tests have been disabled
//      try (ColumnVector result = v1.scan(Aggregation.sum(), ScanType.INCLUSIVE, NullPolicy.INCLUDE);
//           ColumnVector expected = ColumnVector.fromBoxedInts(1, 3, null, null, null, null, null)) {
//        assertColumnsAreEqual(expected, result);
//      }

      try (ColumnVector result = v1.scan(Aggregation.sum(), ScanType.INCLUSIVE, NullPolicy.EXCLUDE);
           ColumnVector expected = ColumnVector.fromBoxedInts(1, 3, null, 6, 11, 19, 29)) {
        assertColumnsAreEqual(expected, result);
      }

//      try (ColumnVector result = v1.scan(Aggregation.sum(), ScanType.EXCLUSIVE, NullPolicy.INCLUDE);
//           ColumnVector expected = ColumnVector.fromBoxedInts(0, 1, 3, 3, 6, 11, 19)) {
//        assertColumnsAreEqual(expected, result);
//      }

      try (ColumnVector result = v1.scan(Aggregation.sum(), ScanType.EXCLUSIVE, NullPolicy.EXCLUDE);
           ColumnVector expected = ColumnVector.fromBoxedInts(0, 1, null, 3, 6, 11, 19)) {
        assertColumnsAreEqual(expected, result);
      }
    }
  }

  @Test
  void testScanMax() {
    // Due to https://github.com/rapidsai/cudf/issues/8462 NullPolicy.INCLUDE
    // tests have been disabled
    try (ColumnVector v1 = ColumnVector.fromBoxedInts(1, 2, null, 3, 5, 8, 10)) {
//      try (ColumnVector result = v1.scan(Aggregation.max(), ScanType.INCLUSIVE, NullPolicy.INCLUDE);
//           ColumnVector expected = ColumnVector.fromBoxedInts(1, 2, null, null, null, null, null)) {
//        assertColumnsAreEqual(expected, result);
//      }

      try (ColumnVector result = v1.scan(Aggregation.max(), ScanType.INCLUSIVE, NullPolicy.EXCLUDE);
           ColumnVector expected = ColumnVector.fromBoxedInts(1, 2, null, 3, 5, 8, 10)) {
        assertColumnsAreEqual(expected, result);
      }

//      try (ColumnVector result = v1.scan(Aggregation.max(), ScanType.EXCLUSIVE, NullPolicy.INCLUDE);
//           ColumnVector expected = ColumnVector.fromBoxedInts(Integer.MIN_VALUE, 1, 2, 2, 3, 5, 8)) {
//        assertColumnsAreEqual(expected, result);
//      }

      try (ColumnVector result = v1.scan(Aggregation.max(), ScanType.EXCLUSIVE, NullPolicy.EXCLUDE);
           ColumnVector expected = ColumnVector.fromBoxedInts(Integer.MIN_VALUE, 1, null, 2, 3, 5, 8)) {
        assertColumnsAreEqual(expected, result);
      }
    }
  }

  @Test
  void testScanMin() {
    // Due to https://github.com/rapidsai/cudf/issues/8462 NullPolicy.INCLUDE
    // tests have been disabled
    try (ColumnVector v1 = ColumnVector.fromBoxedInts(1, 2, null, 3, 5, 8, 10)) {
//      try (ColumnVector result = v1.scan(Aggregation.min(), ScanType.INCLUSIVE, NullPolicy.INCLUDE);
//           ColumnVector expected = ColumnVector.fromBoxedInts(1, 1, null, null, null, null, null)) {
//        assertColumnsAreEqual(expected, result);
//      }

      try (ColumnVector result = v1.scan(Aggregation.min(), ScanType.INCLUSIVE, NullPolicy.EXCLUDE);
           ColumnVector expected = ColumnVector.fromBoxedInts(1, 1, null, 1, 1, 1, 1)) {
        assertColumnsAreEqual(expected, result);
      }

//      try (ColumnVector result = v1.scan(Aggregation.min(), ScanType.EXCLUSIVE, NullPolicy.INCLUDE);
//           ColumnVector expected = ColumnVector.fromBoxedInts(Integer.MAX_VALUE, 1, 1, 1, 1, 1, 1)) {
//        assertColumnsAreEqual(expected, result);
//      }

      try (ColumnVector result = v1.scan(Aggregation.min(), ScanType.EXCLUSIVE, NullPolicy.EXCLUDE);
           ColumnVector expected = ColumnVector.fromBoxedInts(Integer.MAX_VALUE, 1, null, 1, 1, 1, 1)) {
        assertColumnsAreEqual(expected, result);
      }
    }
  }

  @Test
  void testScanProduct() {
    // Due to https://github.com/rapidsai/cudf/issues/8462 NullPolicy.INCLUDE
    // tests have been disabled
    try (ColumnVector v1 = ColumnVector.fromBoxedInts(1, 2, null, 3, 5, 8, 10)) {
//      try (ColumnVector result = v1.scan(Aggregation.product(), ScanType.INCLUSIVE, NullPolicy.INCLUDE);
//           ColumnVector expected = ColumnVector.fromBoxedInts(1, 2, null, null, null, null, null)) {
//        assertColumnsAreEqual(expected, result);
//      }

      try (ColumnVector result = v1.scan(Aggregation.product(), ScanType.INCLUSIVE, NullPolicy.EXCLUDE);
           ColumnVector expected = ColumnVector.fromBoxedInts(1, 2, null, 6, 30, 240, 2400)) {
        assertColumnsAreEqual(expected, result);
      }

//      try (ColumnVector result = v1.scan(Aggregation.product(), ScanType.EXCLUSIVE, NullPolicy.INCLUDE);
//           ColumnVector expected = ColumnVector.fromBoxedInts(1, 1, 2, 2, 6, 30, 240)) {
//        assertColumnsAreEqual(expected, result);
//      }

      try (ColumnVector result = v1.scan(Aggregation.product(), ScanType.EXCLUSIVE, NullPolicy.EXCLUDE);
           ColumnVector expected = ColumnVector.fromBoxedInts(1, 1, null, 2, 6, 30, 240)) {
        assertColumnsAreEqual(expected, result);
      }
    }
  }

  @Test
  void testScanRank() {
    try (ColumnVector col1 = ColumnVector.fromBoxedInts(-97, -97, -97, null, -16, 5, null, null, 6, 6, 34, null);
         ColumnVector col2 = ColumnVector.fromBoxedInts(3, 3, 4, 7, 7, 7, 7, 7, 8, 8, 8, 9);
         ColumnVector struct_order = ColumnVector.makeStruct(col1, col2);
         ColumnVector expected = ColumnVector.fromBoxedInts(
            1, 1, 3, 4, 5, 6, 7, 7, 9, 9, 11, 12)) {
      try (ColumnVector result = struct_order.scan(Aggregation.rank(),
              ScanType.INCLUSIVE, NullPolicy.INCLUDE)) {
        assertColumnsAreEqual(expected, result);
      }

      // Exclude should have identical results
      try (ColumnVector result = struct_order.scan(Aggregation.rank(),
              ScanType.INCLUSIVE, NullPolicy.EXCLUDE)
              ) {
        assertColumnsAreEqual(expected, result);
      }

      // Rank aggregations do not support ScanType.EXCLUSIVE
    }
  }

  @Test
  void testScanDenseRank() {
    try (ColumnVector col1 = ColumnVector.fromBoxedInts(-97, -97, -97, null, -16, 5, null, null, 6, 6, 34, null);
         ColumnVector col2 = ColumnVector.fromBoxedInts(3, 3, 4, 7, 7, 7, 7, 7, 8, 8, 8, 9);
         ColumnVector struct_order = ColumnVector.makeStruct(col1, col2);
         ColumnVector expected = ColumnVector.fromBoxedInts(
            1, 1, 2, 3, 4, 5, 6, 6, 7, 7, 8, 9)) {
      try (ColumnVector result = struct_order.scan(Aggregation.denseRank(),
              ScanType.INCLUSIVE, NullPolicy.INCLUDE)) {
        assertColumnsAreEqual(expected, result);
      }

      // Exclude should have identical results
      try (ColumnVector result = struct_order.scan(Aggregation.denseRank(),
              ScanType.INCLUSIVE, NullPolicy.EXCLUDE)) {
        assertColumnsAreEqual(expected, result);
      }

      // Dense rank aggregations do not support ScanType.EXCLUSIVE
    }
  }

  @Test
  void testWindowStatic() {
    try (Scalar one = Scalar.fromInt(1);
         Scalar two = Scalar.fromInt(2);
         WindowOptions options = WindowOptions.builder()
             .window(two, one)
             .minPeriods(2).build()) {
      try (ColumnVector v1 = ColumnVector.fromInts(5, 4, 7, 6, 8)) {
        try (ColumnVector expected = ColumnVector.fromLongs(9, 16, 17, 21, 14);
             ColumnVector result = v1.rollingWindow(Aggregation.sum(), options)) {
          assertColumnsAreEqual(expected, result);
        }

        try (ColumnVector expected = ColumnVector.fromInts(4, 4, 4, 6, 6);
             ColumnVector result = v1.rollingWindow(Aggregation.min(), options)) {
          assertColumnsAreEqual(expected, result);
        }

        try (ColumnVector expected = ColumnVector.fromInts(5, 7, 7, 8, 8);
             ColumnVector result = v1.rollingWindow(Aggregation.max(), options)) {
          assertColumnsAreEqual(expected, result);
        }

        // The rolling window produces the same result type as the input
        try (ColumnVector expected = ColumnVector.fromDoubles(4.5, 16.0 / 3, 17.0 / 3, 7, 7);
             ColumnVector result = v1.rollingWindow(Aggregation.mean(), options)) {
          assertColumnsAreEqual(expected, result);
        }

        try (ColumnVector expected = ColumnVector.fromBoxedInts(4, 7, 6, 8, null);
             ColumnVector result = v1.rollingWindow(Aggregation.lead(1), options)) {
          assertColumnsAreEqual(expected, result);
        }

        try (ColumnVector expected = ColumnVector.fromBoxedInts(null, 5, 4, 7, 6);
             ColumnVector result = v1.rollingWindow(Aggregation.lag(1), options)) {
          assertColumnsAreEqual(expected, result);
        }

        try (ColumnVector defaultOutput = ColumnVector.fromInts(-1, -2, -3, -4, -5);
             ColumnVector expected = ColumnVector.fromBoxedInts(-1, 5, 4, 7, 6);
             ColumnVector result = v1.rollingWindow(Aggregation.lag(1, defaultOutput), options)) {
          assertColumnsAreEqual(expected, result);
        }
      }
    }
  }

  @Test
  void testWindowStaticCounts() {
    try (Scalar one = Scalar.fromInt(1);
         Scalar two = Scalar.fromInt(2);
         WindowOptions options = WindowOptions.builder()
             .window(two, one)
             .minPeriods(2).build()) {
      try (ColumnVector v1 = ColumnVector.fromBoxedInts(5, 4, null, 6, 8)) {
        try (ColumnVector expected = ColumnVector.fromInts(2, 2, 2, 2, 2);
             ColumnVector result = v1.rollingWindow(Aggregation.count(NullPolicy.EXCLUDE), options)) {
          assertColumnsAreEqual(expected, result);
        }
        try (ColumnVector expected = ColumnVector.fromInts(2, 3, 3, 3, 2);
             ColumnVector result = v1.rollingWindow(Aggregation.count(NullPolicy.INCLUDE), options)) {
          assertColumnsAreEqual(expected, result);
        }
      }
    }
  }

  @Test
  void testWindowDynamicNegative() {
    try (ColumnVector precedingCol = ColumnVector.fromInts(3, 3, 3, 4, 4);
         ColumnVector followingCol = ColumnVector.fromInts(-1, -1, -1, -1, 0)) {
      try (WindowOptions window = WindowOptions.builder()
          .minPeriods(2).window(precedingCol, followingCol).build()) {
        try (ColumnVector v1 = ColumnVector.fromInts(5, 4, 7, 6, 8);
             ColumnVector expected = ColumnVector.fromBoxedLongs(null, null, 9L, 16L, 25L);
             ColumnVector result = v1.rollingWindow(Aggregation.sum(), window)) {
          assertColumnsAreEqual(expected, result);
        }
      }
    }
  }

  @Test
  void testWindowLag() {
    try (Scalar negOne = Scalar.fromInt(-1);
         Scalar two = Scalar.fromInt(2);
         WindowOptions window = WindowOptions.builder()
             .minPeriods(1)
             .window(two, negOne).build()) {
      try (ColumnVector v1 = ColumnVector.fromInts(5, 4, 7, 6, 8);
           ColumnVector expected = ColumnVector.fromBoxedInts(null, 5, 4, 7, 6);
           ColumnVector result = v1.rollingWindow(Aggregation.max(), window)) {
        assertColumnsAreEqual(expected, result);
      }
    }
  }

  @Test
  void testWindowDynamic() {
    try (ColumnVector precedingCol = ColumnVector.fromInts(1, 2, 3, 1, 2);
         ColumnVector followingCol = ColumnVector.fromInts(2, 2, 2, 2, 2)) {
      try (WindowOptions window = WindowOptions.builder().minPeriods(2)
          .window(precedingCol, followingCol).build()) {
        try (ColumnVector v1 = ColumnVector.fromInts(5, 4, 7, 6, 8);
             ColumnVector expected = ColumnVector.fromLongs(16, 22, 30, 14, 14);
             ColumnVector result = v1.rollingWindow(Aggregation.sum(), window)) {
          assertColumnsAreEqual(expected, result);
        }
      }
    }
  }

  @Test
  void testWindowThrowsException() {
    try (Scalar one = Scalar.fromInt(1);
         Scalar two = Scalar.fromInt(2);
         Scalar three = Scalar.fromInt(3);
         ColumnVector arraywindowCol = ColumnVector.fromBoxedInts(1, 2, 3 ,1, 1)) {
      assertThrows(IllegalStateException.class, () -> {
        try (WindowOptions options = WindowOptions.builder()
            .window(three, two).minPeriods(3)
            .window(arraywindowCol, arraywindowCol).build()) {
        }
      });

      assertThrows(IllegalArgumentException.class, () -> {
        try (WindowOptions options = WindowOptions.builder()
            .window(two, one)
            .minPeriods(1)
            .orderByColumnIndex(0)
            .build()) {
          arraywindowCol.rollingWindow(Aggregation.sum(), options);
        }
      });
    }
  }

  @Test
  void testFindAndReplaceAll() {
    try(ColumnVector vector = ColumnVector.fromInts(1, 4, 1, 5, 3, 3, 1, 2, 9, 8);
        ColumnVector oldValues = ColumnVector.fromInts(1, 4, 7); // 7 doesn't exist, nothing to replace
        ColumnVector replacedValues = ColumnVector.fromInts(7, 6, 1);
        ColumnVector expectedVector = ColumnVector.fromInts(7, 6, 7, 5, 3, 3, 7, 2, 9, 8);
        ColumnVector newVector = vector.findAndReplaceAll(oldValues, replacedValues)) {
        assertColumnsAreEqual(expectedVector, newVector);
    }
  }

  @Test
  void testFindAndReplaceAllFloat() {
    try(ColumnVector vector = ColumnVector.fromFloats(1.0f, 4.2f, 1.3f, 5.7f, 3f, 3f, 1.0f, 2.6f, 0.9f, 8.3f);
        ColumnVector oldValues = ColumnVector.fromFloats(1.0f, 4.2f, 7); // 7 doesn't exist, nothing to replace
        ColumnVector replacedValues = ColumnVector.fromFloats(7.3f, 6.7f, 1.0f);
        ColumnVector expectedVector = ColumnVector.fromFloats(7.3f, 6.7f, 1.3f, 5.7f, 3f, 3f, 7.3f, 2.6f, 0.9f, 8.3f);
        ColumnVector newVector = vector.findAndReplaceAll(oldValues, replacedValues)) {
      assertColumnsAreEqual(expectedVector, newVector);
    }
  }

  @Test
  void testFindAndReplaceAllTimeUnits() {
    try(ColumnVector vector = ColumnVector.timestampMicroSecondsFromLongs(1l, 1l, 2l, 8l);
        ColumnVector oldValues = ColumnVector.timestampMicroSecondsFromLongs(1l, 2l, 7l); // 7 dosn't exist, nothing to replace
        ColumnVector replacedValues = ColumnVector.timestampMicroSecondsFromLongs(9l, 4l, 0l);
        ColumnVector expectedVector = ColumnVector.timestampMicroSecondsFromLongs(9l, 9l, 4l, 8l);
        ColumnVector newVector = vector.findAndReplaceAll(oldValues, replacedValues)) {
      assertColumnsAreEqual(expectedVector, newVector);
    }
  }

  @Test
  void testFindAndReplaceAllMixingTypes() {
    try(ColumnVector vector = ColumnVector.fromInts(1, 4, 1, 5, 3, 3, 1, 2, 9, 8);
        ColumnVector oldValues = ColumnVector.fromInts(1, 4, 7); // 7 doesn't exist, nothing to replace
        ColumnVector replacedValues = ColumnVector.fromFloats(7.0f, 6, 1)) {
      assertThrows(CudfException.class, () -> vector.findAndReplaceAll(oldValues, replacedValues));
    }
  }

  @Test
  void testFindAndReplaceAllStrings() {
    try(ColumnVector vector = ColumnVector.fromStrings("spark", "scala", "spark", "hello", "code");
        ColumnVector oldValues = ColumnVector.fromStrings("spark","code","hello");
        ColumnVector replacedValues = ColumnVector.fromStrings("sparked", "codec", "hi");
        ColumnVector expectedValues = ColumnVector.fromStrings("sparked", "scala", "sparked", "hi", "codec");
        ColumnVector cv = vector.findAndReplaceAll(oldValues, replacedValues)) {
      assertColumnsAreEqual(expectedValues, cv);
    }
  }

  @Test
  void testFindAndReplaceAllWithNull() {
    try(ColumnVector vector = ColumnVector.fromBoxedInts(1, 4, 1, 5, 3, 3, 1, null, 9, 8);
        ColumnVector oldValues = ColumnVector.fromBoxedInts(1, 4, 8);
        ColumnVector replacedValues = ColumnVector.fromBoxedInts(7, 6, null);
        ColumnVector expectedVector = ColumnVector.fromBoxedInts(7, 6, 7, 5, 3, 3, 7, null, 9, null);
        ColumnVector newVector = vector.findAndReplaceAll(oldValues, replacedValues)) {
      assertColumnsAreEqual(expectedVector, newVector);
    }
  }

  @Test
  void testFindAndReplaceAllNulllWithValue() {
    // null values cannot be replaced using findAndReplaceAll();
    try(ColumnVector vector = ColumnVector.fromBoxedInts(1, 4, 1, 5, 3, 3, 1, null, 9, 8);
        ColumnVector oldValues = ColumnVector.fromBoxedInts(1, 4, null);
        ColumnVector replacedValues = ColumnVector.fromBoxedInts(7, 6, 8)) {
      assertThrows(CudfException.class, () -> vector.findAndReplaceAll(oldValues, replacedValues));
    }
  }

  @Test
  void testFindAndReplaceAllFloatNan() {
    // Float.NaN != Float.NaN therefore it cannot be replaced
    try(ColumnVector vector = ColumnVector.fromFloats(1.0f, 4.2f, 1.3f, 5.7f, 3f, 3f, 1.0f, 2.6f, Float.NaN, 8.3f);
        ColumnVector oldValues = ColumnVector.fromFloats(1.0f, 4.2f, Float.NaN);
        ColumnVector replacedValues = ColumnVector.fromFloats(7.3f, 6.7f, 0);
        ColumnVector expectedVector = ColumnVector.fromFloats(7.3f, 6.7f, 1.3f, 5.7f, 3f, 3f, 7.3f, 2.6f, Float.NaN, 8.3f);
        ColumnVector newVector = vector.findAndReplaceAll(oldValues, replacedValues)) {
      assertColumnsAreEqual(expectedVector, newVector);
    }
  }

  @Test
  void testFindAndReplaceAllWithFloatNan() {
    try(ColumnVector vector = ColumnVector.fromFloats(1.0f, 4.2f, 1.3f, 5.7f, 3f, 3f, 1.0f, 2.6f, Float.NaN, 8.3f);
        ColumnVector oldValues = ColumnVector.fromFloats(1.0f, 4.2f, 8.3f);
        ColumnVector replacedValues = ColumnVector.fromFloats(7.3f, Float.NaN, 0);
        ColumnVector expectedVector = ColumnVector.fromFloats(7.3f, Float.NaN, 1.3f, 5.7f, 3f, 3f, 7.3f, 2.6f, Float.NaN, 0);
        ColumnVector newVector = vector.findAndReplaceAll(oldValues, replacedValues)) {
      assertColumnsAreEqual(expectedVector, newVector);
    }
  }

  @Test
  void emptyStringColumnFindReplaceAll() {
    try (ColumnVector cv = ColumnVector.fromStrings(null, "A", "B", "C",   "");
         ColumnVector expected = ColumnVector.fromStrings(null, "A", "B", "C",   null);
         ColumnVector from = ColumnVector.fromStrings("");
         ColumnVector to = ColumnVector.fromStrings((String)null);
         ColumnVector replaced = cv.findAndReplaceAll(from, to)) {
      assertColumnsAreEqual(expected, replaced);
    }
  }

  @Test
  void testBitCast() {
    try (ColumnVector cv = ColumnVector.decimalFromLongs(-2, 1L, 2L, 100L, 552L);
         ColumnVector expected = ColumnVector.fromLongs(1L, 2L, 100L, 552L);
         ColumnView casted = cv.bitCastTo(DType.INT64)) {
      assertColumnsAreEqual(expected, casted);
    }
  }

  @Test
  void testFixedWidthCast() {
    int[] values = new int[]{1,3,4,5,2};
    long[] longValues = Arrays.stream(values).asLongStream().toArray();
    double[] doubleValues = Arrays.stream(values).asDoubleStream().toArray();
    byte[] byteValues = new byte[values.length];
    float[] floatValues = new float[values.length];
    short[] shortValues = new short[values.length];
    IntStream.range(0, values.length).forEach(i -> {
      byteValues[i] = (byte)values[i];
      floatValues[i] = (float)values[i];
      shortValues[i] = (short)values[i];
    });

    try (ColumnVector cv = ColumnVector.fromInts(values);
         ColumnVector expectedUnsignedInts = ColumnVector.fromUnsignedInts(values);
         ColumnVector unsignedInts = cv.asUnsignedInts();
         ColumnVector expectedBytes = ColumnVector.fromBytes(byteValues);
         ColumnVector bytes = cv.asBytes();
         ColumnVector expectedUnsignedBytes = ColumnVector.fromUnsignedBytes(byteValues);
         ColumnVector unsignedBytes = cv.asUnsignedBytes();
         ColumnVector expectedFloats = ColumnVector.fromFloats(floatValues);
         ColumnVector floats = cv.asFloats();
         ColumnVector expectedDoubles = ColumnVector.fromDoubles(doubleValues);
         ColumnVector doubles = cv.asDoubles();
         ColumnVector expectedLongs = ColumnVector.fromLongs(longValues);
         ColumnVector longs = cv.asLongs();
         ColumnVector expectedUnsignedLongs = ColumnVector.fromUnsignedLongs(longValues);
         ColumnVector unsignedLongs = cv.asUnsignedLongs();
         ColumnVector expectedShorts = ColumnVector.fromShorts(shortValues);
         ColumnVector shorts = cv.asShorts();
         ColumnVector expectedUnsignedShorts = ColumnVector.fromUnsignedShorts(shortValues);
         ColumnVector unsignedShorts = cv.asUnsignedShorts();
         ColumnVector expectedDays = ColumnVector.daysFromInts(values);
         ColumnVector days = cv.asTimestampDays();
         ColumnVector expectedUs = ColumnVector.timestampMicroSecondsFromLongs(longValues);
         ColumnVector us = longs.asTimestampMicroseconds();
         ColumnVector expectedNs = ColumnVector.timestampNanoSecondsFromLongs(longValues);
         ColumnVector ns = longs.asTimestampNanoseconds();
         ColumnVector expectedMs = ColumnVector.timestampMilliSecondsFromLongs(longValues);
         ColumnVector ms = longs.asTimestampMilliseconds();
         ColumnVector expectedS = ColumnVector.timestampSecondsFromLongs(longValues);
         ColumnVector s = longs.asTimestampSeconds();) {
      assertColumnsAreEqual(expectedUnsignedInts, unsignedInts);
      assertColumnsAreEqual(expectedBytes, bytes);
      assertColumnsAreEqual(expectedUnsignedBytes, unsignedBytes);
      assertColumnsAreEqual(expectedShorts, shorts);
      assertColumnsAreEqual(expectedUnsignedShorts, unsignedShorts);
      assertColumnsAreEqual(expectedLongs, longs);
      assertColumnsAreEqual(expectedUnsignedLongs, unsignedLongs);
      assertColumnsAreEqual(expectedDoubles, doubles);
      assertColumnsAreEqual(expectedFloats, floats);
      assertColumnsAreEqual(expectedDays, days);
      assertColumnsAreEqual(expectedUs, us);
      assertColumnsAreEqual(expectedMs, ms);
      assertColumnsAreEqual(expectedNs, ns);
      assertColumnsAreEqual(expectedS, s);
    }
  }

  @Test
  void testCastByteToString() {

    Byte[] byteValues = {1, 3, 45, -0, null, Byte.MIN_VALUE, Byte.MAX_VALUE};
    String[] stringByteValues = getStringArray(byteValues);

    testCastFixedWidthToStringsAndBack(DType.INT8, () -> ColumnVector.fromBoxedBytes(byteValues), () -> ColumnVector.fromStrings(stringByteValues));
  }

  @Test
  void testCastShortToString() {

    Short[] shortValues = {1, 3, 45, -0, null, Short.MIN_VALUE, Short.MAX_VALUE};
    String[] stringShortValues = getStringArray(shortValues);

    testCastFixedWidthToStringsAndBack(DType.INT16, () -> ColumnVector.fromBoxedShorts(shortValues), () -> ColumnVector.fromStrings(stringShortValues));
  }

  @Test
  void testCastIntToString() {
    Integer[] integerArray = {1, -2, 3, null, 8, Integer.MIN_VALUE, Integer.MAX_VALUE};
    String[] stringIntValues = getStringArray(integerArray);

    testCastFixedWidthToStringsAndBack(DType.INT32, () -> ColumnVector.fromBoxedInts(integerArray), () -> ColumnVector.fromStrings(stringIntValues));
  }

  @Test
  void testCastLongToString() {

    Long[] longValues = {null, 3l, 2l, -43l, null, Long.MIN_VALUE, Long.MAX_VALUE};
    String[] stringLongValues = getStringArray(longValues);

    testCastFixedWidthToStringsAndBack(DType.INT64, () -> ColumnVector.fromBoxedLongs(longValues), () -> ColumnVector.fromStrings(stringLongValues));
  }

  @Test
  void testCastFloatToString() {

    Float[] floatValues = {Float.NaN, null, 03f, -004f, 12f};
    String[] stringFloatValues = getStringArray(floatValues);

    testCastFixedWidthToStringsAndBack(DType.FLOAT32, () -> ColumnVector.fromBoxedFloats(floatValues), () -> ColumnVector.fromStrings(stringFloatValues));
  }

  @Test
  void testCastDoubleToString() {

    Double[] doubleValues = {Double.NaN, Double.NEGATIVE_INFINITY, 4d, 98d, null, Double.POSITIVE_INFINITY};
    //Creating the string array manually because of the way cudf converts POSITIVE_INFINITY to "Inf" instead of "INFINITY"
    String[] stringDoubleValues = {"NaN","-Inf", "4.0", "98.0", null, "Inf"};

    testCastFixedWidthToStringsAndBack(DType.FLOAT64, () -> ColumnVector.fromBoxedDoubles(doubleValues), () -> ColumnVector.fromStrings(stringDoubleValues));
  }

  @Test
  void testCastBoolToString() {

    Boolean[] booleans = {true, false, false};
    String[] stringBools = getStringArray(booleans);

    testCastFixedWidthToStringsAndBack(DType.BOOL8, () -> ColumnVector.fromBoxedBooleans(booleans), () -> ColumnVector.fromStrings(stringBools));
  }

  @Test
  void testCastDecimal32ToString() {

    Integer[] unScaledValues = {0, null, 3, 2, -43, null, 5234, -73451, 348093, -234810};
    String[] strDecimalValues = new String[unScaledValues.length];
    for (int scale : new int[]{-2, -1, 0, 1, 2}) {
      for (int i = 0; i < strDecimalValues.length; i++) {
        Long value = unScaledValues[i] == null ? null : Long.valueOf(unScaledValues[i]);
        strDecimalValues[i] = dumpDecimal(value, scale);
      }

      testCastFixedWidthToStringsAndBack(DType.create(DType.DTypeEnum.DECIMAL32, scale),
          () -> ColumnVector.decimalFromBoxedInts(scale, unScaledValues),
          () -> ColumnVector.fromStrings(strDecimalValues));
    }
  }

  @Test
  void testCastDecimal64ToString() {

    Long[] unScaledValues = {0l, null, 3l, 2l, -43l, null, 234802l, -94582l, 1234208124l, -2342348023812l};
    String[] strDecimalValues = new String[unScaledValues.length];
    for (int scale : new int[]{-5, -2, -1, 0, 1, 2, 5}) {
      for (int i = 0; i < strDecimalValues.length; i++) {
        strDecimalValues[i] = dumpDecimal(unScaledValues[i], scale);
        System.out.println(strDecimalValues[i]);
      }

      testCastFixedWidthToStringsAndBack(DType.create(DType.DTypeEnum.DECIMAL64, scale),
          () -> ColumnVector.decimalFromBoxedLongs(scale, unScaledValues),
          () -> ColumnVector.fromStrings(strDecimalValues));
    }
  }

  /**
   * Helper function to create decimal strings which can be processed by castStringToDecimal functor.
   * We can not simply create decimal string via `String.valueOf`, because castStringToDecimal doesn't
   * support scientific notations so far.
   *
   * issue for scientific notation: https://github.com/rapidsai/cudf/issues/7665
   */
  private static String dumpDecimal(Long unscaledValue, int scale) {
    if (unscaledValue == null) return null;

    StringBuilder builder = new StringBuilder();
    if (unscaledValue < 0) builder.append('-');
    String absValue = String.valueOf(Math.abs(unscaledValue));

    if (scale >= 0) {
      builder.append(absValue);
      for (int i = 0; i < scale; i++) builder.append('0');
      return builder.toString();
    }

    if (absValue.length() <= -scale) {
      builder.append('0').append('.');
      for (int i = 0; i < -scale - absValue.length(); i++) builder.append('0');
      builder.append(absValue);
    } else {
      int split = absValue.length() + scale;
      builder.append(absValue.substring(0, split))
          .append('.')
          .append(absValue.substring(split));
    }
    return builder.toString();
  }

  private static <T> String[] getStringArray(T[] input) {
    String[] result = new String[input.length];
    for (int i = 0 ; i < input.length ; i++) {
      if (input[i] == null) {
        result[i] = null;
      } else {
        result[i] = String.valueOf(input[i]);
      }
    }
    return result;
  }

  private static void testCastFixedWidthToStringsAndBack(DType type, Supplier<ColumnVector> fixedWidthSupplier,
                                                         Supplier<ColumnVector> stringColumnSupplier) {
    try (ColumnVector fixedWidthColumn = fixedWidthSupplier.get();
         ColumnVector stringColumn = stringColumnSupplier.get();
         ColumnVector fixedWidthCastedToString = fixedWidthColumn.castTo(DType.STRING);
         ColumnVector stringCastedToFixedWidth = stringColumn.castTo(type)) {
      assertColumnsAreEqual(stringColumn, fixedWidthCastedToString);
      assertColumnsAreEqual(fixedWidthColumn, stringCastedToFixedWidth);
    }
  }

  @Test
  void testCastIntToDecimal() {
    testCastNumericToDecimalsAndBack(DType.INT32, true, 0,
        () -> ColumnVector.fromBoxedInts(1, -21, 345, null, 8008, Integer.MIN_VALUE, Integer.MAX_VALUE),
        () -> ColumnVector.fromBoxedInts(1, -21, 345, null, 8008, Integer.MIN_VALUE, Integer.MAX_VALUE),
        new Long[]{1L, -21L, 345L, null, 8008L, (long) Integer.MIN_VALUE, (long) Integer.MAX_VALUE}
    );
    testCastNumericToDecimalsAndBack(DType.INT32, false, -2,
        () -> ColumnVector.fromBoxedInts(1, -21, 345, null, 8008, 0, 123456),
        () -> ColumnVector.fromBoxedInts(1, -21, 345, null, 8008, 0, 123456),
        new Long[]{100L, -2100L, 34500L, null, 800800L, 0L, 12345600L}
    );
    testCastNumericToDecimalsAndBack(DType.INT32, false, 2,
        () -> ColumnVector.fromBoxedInts(1, -21, 345, null, 8008, 0, 123456),
        () -> ColumnVector.fromBoxedInts(0, 0, 300, null, 8000, 0, 123400),
        new Long[]{0L, 0L, 3L, null, 80L, 0L, 1234L}
    );
  }

  @Test
  void testCastLongToDecimal() {
    testCastNumericToDecimalsAndBack(DType.INT64, false, 0,
        () -> ColumnVector.fromBoxedLongs(1L, -21L, 345L, null, 8008L, Long.MIN_VALUE, Long.MAX_VALUE),
        () -> ColumnVector.fromBoxedLongs(1L, -21L, 345L, null, 8008L, Long.MIN_VALUE, Long.MAX_VALUE),
        new Long[]{1L, -21L, 345L, null, 8008L, Long.MIN_VALUE, Long.MAX_VALUE}
    );
    testCastNumericToDecimalsAndBack(DType.INT64, false, -1,
        () -> ColumnVector.fromBoxedLongs(1L, -21L, 345L, null, 8008L, 0L, 123456L),
        () -> ColumnVector.fromBoxedLongs(1L, -21L, 345L, null, 8008L, 0L, 123456L),
        new Long[]{10L, -210L, 3450L, null, 80080L, 0L, 1234560L}
    );
    testCastNumericToDecimalsAndBack(DType.INT64, false, 1,
        () -> ColumnVector.fromBoxedLongs(1L, -21L, 345L, null, 8018L, 0L, 123456L),
        () -> ColumnVector.fromBoxedLongs(0L, -20L, 340L, null, 8010L, 0L, 123450L),
        new Long[]{0L, -2L, 34L, null, 801L, 0L, 12345L}
    );
  }

  @Test
  void testCastFloatToDecimal() {
    testCastNumericToDecimalsAndBack(DType.FLOAT32, true, 0,
        () -> ColumnVector.fromBoxedFloats(1.0f, 2.1f, -3.23f, null, 2.41281f, 1378952.001f),
        () -> ColumnVector.fromBoxedFloats(1f, 2f, -3f, null, 2f, 1378952f),
        new Long[]{1L, 2L, -3L, null, 2L, 1378952L}
    );
    testCastNumericToDecimalsAndBack(DType.FLOAT32, true, -1,
        () -> ColumnVector.fromBoxedFloats(1.0f, 2.1f, -3.23f, null, 2.41281f, 1378952.001f),
        () -> ColumnVector.fromBoxedFloats(1f, 2.1f, -3.2f, null, 2.4f, 1378952f),
        new Long[]{10L, 21L, -32L, null, 24L, 13789520L}
    );
    testCastNumericToDecimalsAndBack(DType.FLOAT32, true, 1,
        () -> ColumnVector.fromBoxedFloats(1.0f, 21.1f, -300.23f, null, 24128.1f, 1378952.001f),
        () -> ColumnVector.fromBoxedFloats(0f, 20f, -300f, null, 24120f, 1378950f),
        new Long[]{0L, 2L, -30L, null, 2412L, 137895L}
    );
  }

  @Test
  void testCastDoubleToDecimal() {
    testCastNumericToDecimalsAndBack(DType.FLOAT64, false, 0,
        () -> ColumnVector.fromBoxedDoubles(1.0, 2.1, -3.23, null, 2.41281, (double) Long.MAX_VALUE),
        () -> ColumnVector.fromBoxedDoubles(1.0, 2.0, -3.0, null, 2.0, (double) Long.MAX_VALUE),
        new Long[]{1L, 2L, -3L, null, 2L, Long.MAX_VALUE}
    );
    testCastNumericToDecimalsAndBack(DType.FLOAT64, false, -2,
        () -> ColumnVector.fromBoxedDoubles(1.0, 2.1, -3.23, null, 2.41281, -55.01999),
        () -> ColumnVector.fromBoxedDoubles(1.0, 2.1, -3.23, null, 2.41, -55.01),
        new Long[]{100L, 210L, -323L, null, 241L, -5501L}
    );
    testCastNumericToDecimalsAndBack(DType.FLOAT64, false, 1,
        () -> ColumnVector.fromBoxedDoubles(1.0, 23.1, -3089.23, null, 200.41281, -199.01999),
        () -> ColumnVector.fromBoxedDoubles(0.0, 20.0, -3080.0, null, 200.0, -190.0),
        new Long[]{0L, 2L, -308L, null, 20L, -19L}
    );
  }

  @Test
  void testCastDecimalToDecimal() {
    // DECIMAL32(scale: 0) -> DECIMAL32(scale: 0)
    testCastNumericToDecimalsAndBack(DType.create(DType.DTypeEnum.DECIMAL32, 0), true, -0,
        () -> ColumnVector.decimalFromInts(0, 1, 12, -234, 5678, Integer.MIN_VALUE / 100),
        () -> ColumnVector.decimalFromInts(0, 1, 12, -234, 5678, Integer.MIN_VALUE / 100),
        new Long[]{1L, 12L, -234L, 5678L, (long) Integer.MIN_VALUE / 100}
    );
    // DECIMAL32(scale: 0) -> DECIMAL64(scale: -2)
    testCastNumericToDecimalsAndBack(DType.create(DType.DTypeEnum.DECIMAL32, 0), false, -2,
        () -> ColumnVector.decimalFromInts(0, 1, 12, -234, 5678, Integer.MIN_VALUE / 100),
        () -> ColumnVector.decimalFromInts(0, 1, 12, -234, 5678, Integer.MIN_VALUE / 100),
        new Long[]{100L, 1200L, -23400L, 567800L, (long) Integer.MIN_VALUE / 100 * 100}
    );
    // DECIMAL64(scale: -3) -> DECIMAL64(scale: -1)
    DType dt = DType.create(DType.DTypeEnum.DECIMAL64, -3);
    testCastNumericToDecimalsAndBack(dt, false, -1,
        () -> ColumnVector.decimalFromDoubles(dt, RoundingMode.UNNECESSARY, -1000.1, 1.222, 0.03, -4.678, 16789431.0),
        () -> ColumnVector.decimalFromDoubles(dt, RoundingMode.UNNECESSARY, -1000.1, 1.2, 0, -4.6, 16789431.0),
        new Long[]{-10001L, 12L, 0L, -46L, 167894310L}
    );
    // DECIMAL64(scale: -3) -> DECIMAL64(scale: 2)
    DType dt2 = DType.create(DType.DTypeEnum.DECIMAL64, -3);
    testCastNumericToDecimalsAndBack(dt2, false, 2,
        () -> ColumnVector.decimalFromDoubles(dt2, RoundingMode.UNNECESSARY, -1013.1, 14.222, 780.03, -4.678, 16789431.0),
        () -> ColumnVector.decimalFromDoubles(dt2, RoundingMode.UNNECESSARY, -1000, 0, 700, 0, 16789400),
        new Long[]{-10L, 0L, 7L, 0L, 167894L}
    );
    // DECIMAL64(scale: -3) -> DECIMAL32(scale: -3)
    testCastNumericToDecimalsAndBack(dt2, true, -3,
        () -> ColumnVector.decimalFromDoubles(dt2, RoundingMode.UNNECESSARY, -1013.1, 14.222, 780.03, -4.678, 16789.0),
        () -> ColumnVector.decimalFromDoubles(dt2, RoundingMode.UNNECESSARY, -1013.1, 14.222, 780.03, -4.678, 16789.0),
        new Long[]{-1013100L, 14222L, 780030L, -4678L, 16789000L}
    );
  }

  private static void testCastNumericToDecimalsAndBack(DType sourceType, boolean isDec32, int scale,
                                                       Supplier<ColumnVector> sourceData,
                                                       Supplier<ColumnVector> returnData,
                                                       Long[] unscaledDecimal) {
    DType decimalType = DType.create(isDec32 ? DType.DTypeEnum.DECIMAL32 : DType.DTypeEnum.DECIMAL64, scale);
    try (ColumnVector sourceColumn = sourceData.get();
         ColumnVector expectedColumn = returnData.get();
         ColumnVector decimalColumn = sourceColumn.castTo(decimalType);
         HostColumnVector hostDecimalColumn = decimalColumn.copyToHost();
         ColumnVector returnColumn = decimalColumn.castTo(sourceType)) {
      for (int i = 0; i < sourceColumn.rows; i++) {
        Long actual = hostDecimalColumn.isNull(i) ? null :
            (isDec32 ? hostDecimalColumn.getInt(i) : hostDecimalColumn.getLong(i));
        assertEquals(unscaledDecimal[i], actual);
      }
      assertColumnsAreEqual(expectedColumn, returnColumn);
    }
  }

  @Test
  void testIsTimestamp() {
      final String[] TIMESTAMP_STRINGS = {
          "2018-07-04 12:00:00",
          "",
          null,
          "2023-01-25",
          "2023-01-25 07:32:12",
          "2018-07-04 12:00:00"
      };

      try (ColumnVector timestampStrings = ColumnVector.fromStrings(TIMESTAMP_STRINGS);
           ColumnVector isTimestamp = timestampStrings.isTimestamp("%Y-%m-%d %H:%M:%S");
           ColumnVector expected = ColumnVector.fromBoxedBooleans(
               true, false, null, false, true, true)) {
          assertColumnsAreEqual(expected, isTimestamp);
      }

      try (ColumnVector timestampStrings = ColumnVector.fromStrings(TIMESTAMP_STRINGS);
           ColumnVector isTimestamp = timestampStrings.isTimestamp("%Y-%m-%d");
           ColumnVector expected = ColumnVector.fromBoxedBooleans(
                   true, false, null, true, true, true)) {
          assertColumnsAreEqual(expected, isTimestamp);
      }
  }

  @Test
  void testCastTimestampAsString() {
    final String[] TIMES_S_STRING = {
        "2018-07-04 12:00:00",
        "2023-01-25 07:32:12",
        "2018-07-04 12:00:00"};

    final long[] TIMES_S = {
        1530705600L,   //'2018-07-04 12:00:00'
        1674631932L,   //'2023-01-25 07:32:12'
        1530705600L};  //'2018-07-04 12:00:00'

    final long[] TIMES_NS = {
        1530705600115254330L,   //'2018-07-04 12:00:00.115254330'
        1674631932929861604L,   //'2023-01-25 07:32:12.929861604'
        1530705600115254330L};  //'2018-07-04 12:00:00.115254330'

    final String[] TIMES_NS_STRING = {
        "2018-07-04 12:00:00.115254330",
        "2023-01-25 07:32:12.929861604",
        "2018-07-04 12:00:00.115254330"};

    // all supported formats by cudf
    final String[] TIMES_NS_STRING_ALL = {
        "04::07::18::2018::12::00::00::115254330",
        "25::01::23::2023::07::32::12::929861604",
        "04::07::18::2018::12::00::00::115254330"};

    // Seconds
    try (ColumnVector s_string_times = ColumnVector.fromStrings(TIMES_S_STRING);
         ColumnVector s_timestamps = ColumnVector.timestampSecondsFromLongs(TIMES_S);
         ColumnVector timestampsAsStrings = s_timestamps.asStrings("%Y-%m-%d %H:%M:%S");
         ColumnVector timestampsAsStringsUsingDefaultFormat = s_timestamps.asStrings()) {
      assertColumnsAreEqual(s_string_times, timestampsAsStrings);
      assertColumnsAreEqual(timestampsAsStringsUsingDefaultFormat, timestampsAsStrings);
    }

    // Nanoseconds
    try (ColumnVector ns_string_times = ColumnVector.fromStrings(TIMES_NS_STRING);
         ColumnVector ns_timestamps = ColumnVector.timestampNanoSecondsFromLongs(TIMES_NS);
         ColumnVector ns_string_times_all = ColumnVector.fromStrings(TIMES_NS_STRING_ALL);
         ColumnVector allSupportedFormatsTimestampAsStrings = ns_timestamps.asStrings("%d::%m::%y::%Y::%H::%M::%S::%9f");
         ColumnVector timestampsAsStrings = ns_timestamps.asStrings("%Y-%m-%d %H:%M:%S.%9f")) {
      assertColumnsAreEqual(ns_string_times, timestampsAsStrings);
      assertColumnsAreEqual(allSupportedFormatsTimestampAsStrings, ns_string_times_all);
    }
  }

  @Test
  @Disabled("Negative timestamp values are not currently supported. " +
      "See github issue https://github.com/rapidsai/cudf/issues/3116 for details")
  void testCastNegativeTimestampAsString() {
    final String[] NEG_TIME_S_STRING = {"1965-10-26 14:01:12",
        "1960-02-06 19:22:11"};

    final long[] NEG_TIME_S = {-131968728L,   //'1965-10-26 14:01:12'
        -312439069L};   //'1960-02-06 19:22:11'

    final long[] NEG_TIME_NS = {-131968727761702469L};   //'1965-10-26 14:01:12.238297531'

    final String[] NEG_TIME_NS_STRING = {"1965-10-26 14:01:12.238297531"};

    // Seconds
    try (ColumnVector unsupported_s_string_times = ColumnVector.fromStrings(NEG_TIME_S_STRING);
         ColumnVector unsupported_s_timestamps = ColumnVector.timestampSecondsFromLongs(NEG_TIME_S)) {
      assertColumnsAreEqual(unsupported_s_string_times, unsupported_s_timestamps);
    }

    // Nanoseconds
    try (ColumnVector unsupported_ns_string_times = ColumnVector.fromStrings(NEG_TIME_NS_STRING);
         ColumnVector unsupported_ns_timestamps = ColumnVector.timestampSecondsFromLongs(NEG_TIME_NS)) {
      assertColumnsAreEqual(unsupported_ns_string_times, unsupported_ns_timestamps);
    }
  }

  @Test
  void testCastStringToByteList() {
    List<Byte> list1 = Arrays.asList((byte)0x54, (byte)0x68, (byte)0xc3, (byte)0xa9, (byte)0x73,
      (byte)0xc3, (byte)0xa9);
    List<Byte> list2 = null;
    List<Byte> list3 = Arrays.asList((byte)0x0d, (byte)0xed, (byte)0x9c, (byte)0xa0, (byte)0xc3,
      (byte)0xa9, (byte)0xed, (byte)0x9c, (byte)0xa1);
    List<Byte> list4 = Arrays.asList((byte)0x41, (byte)0x52, (byte)0xc3, (byte)0xa9);
    List<Byte> list5 = Arrays.asList((byte)0x5c, (byte)0x54, (byte)0x48, (byte)0x45, (byte)0x09,
      (byte)0x38, (byte)0xed, (byte)0x9c, (byte)0xa0);
    List<Byte> list6 = Arrays.asList((byte)0x74, (byte)0xc3, (byte)0xa9, (byte)0x73, (byte)0x74,
      (byte)0x20, (byte)0x73, (byte)0x74, (byte)0x72, (byte)0x69, (byte)0x6e, (byte)0x67, (byte)0x73);
    List<Byte> list7 = Arrays.asList();
    List<Byte> list8 = Arrays.asList((byte)0xc3, (byte)0xa9, (byte)0xc3, (byte)0xa9);

    try(ColumnVector cv = ColumnVector.fromStrings("Thésé", null, "\r\ud720é\ud721", "ARé",
    "\\THE\t8\ud720", "tést strings", "", "éé");
        ColumnVector res = cv.asByteList(true);
        ColumnVector expected = ColumnVector.fromLists(new HostColumnVector.ListType(true,
          new HostColumnVector.BasicType(true, DType.INT8)), list1, list2, list3, list4, list5,
          list6, list7, list8)) {
      assertColumnsAreEqual(expected, res);
    }
  }

  @Test
  void testCastIntegerToByteList() {
    List<Byte> list1 = Arrays.asList((byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00);
    List<Byte> list2 = Arrays.asList((byte)0x00, (byte)0x00, (byte)0x00, (byte)0x64);
    List<Byte> list3 = Arrays.asList((byte)0xff, (byte)0xff, (byte)0xff, (byte)0x9c);
    List<Byte> list4 = Arrays.asList((byte)0x80, (byte)0x00, (byte)0x00, (byte)0x00);
    List<Byte> list5 = Arrays.asList((byte)0x7f, (byte)0xff, (byte)0xff, (byte)0xff);

    try(ColumnVector cv = ColumnVector.fromBoxedInts(0, 100, -100, Integer.MIN_VALUE, Integer.MAX_VALUE);
        ColumnVector res = cv.asByteList(true);
        ColumnVector expected = ColumnVector.fromLists(new HostColumnVector.ListType(true,
          new HostColumnVector.BasicType(true, DType.UINT8)), list1, list2, list3, list4, list5)) {
      assertColumnsAreEqual(expected, res);
    }
  }

  @Test
  void testCastFloatToByteList() {
    List<Byte> list1 = Arrays.asList((byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00);
    List<Byte> list2 = Arrays.asList((byte)0x00, (byte)0x00, (byte)0xc8, (byte)0x42);
    List<Byte> list3 = Arrays.asList((byte)0x00, (byte)0x00, (byte)0xc8, (byte)0xc2);
    List<Byte> list4 = Arrays.asList((byte)0x00, (byte)0x00, (byte)0xc0, (byte)0x7f);
    List<Byte> list5 = Arrays.asList((byte)0xff, (byte)0xff, (byte)0x7f, (byte)0x7f);
    List<Byte> list6 = Arrays.asList((byte)0x00, (byte)0x00, (byte)0x80, (byte)0xff);

    try(ColumnVector cv = ColumnVector.fromBoxedFloats((float)0.0, (float)100.0, (float)-100.0,
          -Float.NaN, Float.MAX_VALUE, Float.NEGATIVE_INFINITY);
        ColumnVector res = cv.asByteList(false);
        ColumnVector expected = ColumnVector.fromLists(new HostColumnVector.ListType(true,
          new HostColumnVector.BasicType(true, DType.UINT8)), list1, list2, list3, list4, list5, list6)) {
      assertColumnsAreEqual(expected, res);
    }
  }

  @Test
  void testGetBytesFromList() {
    List<Byte> list = Arrays.asList((byte)0x41, (byte)0x52, (byte)0xc3, (byte)0xa9);
    try(ColumnVector cv = ColumnVector.fromStrings("ARé");
        ColumnVector bytes = cv.asByteList(false);
        HostColumnVector hostRes = bytes.copyToHost()) {
      byte[] result = hostRes.getBytesFromList(0);
      for(int i = 0; i < result.length; i++) {
        assertEquals(list.get(i).byteValue(), result[i]);
      }
    }
  }

  @Test
  void testContainsScalar() {
    try (ColumnVector columnVector = ColumnVector.fromInts(1, 43, 42, 11, 2);
    Scalar s0 = Scalar.fromInt(3);
    Scalar s1 = Scalar.fromInt(43)) {
      assertFalse(columnVector.contains(s0));
      assertTrue(columnVector.contains(s1));
    }
  }

  @Test
  void testContainsVector() {
    try (ColumnVector columnVector = ColumnVector.fromBoxedInts(1, null, 43, 42, 11, 2);
         ColumnVector cv0 = ColumnVector.fromBoxedInts(1, 3, null, 11);
         ColumnVector expected = ColumnVector.fromBoxedBooleans(true, null, false, false, true, false);
         ColumnVector result = columnVector.contains(cv0)) {
      assertColumnsAreEqual(expected, result);
    }
    try (ColumnVector columnVector = ColumnVector.fromStrings("1", "43", "42", "11", "2");
         ColumnVector cv0 = ColumnVector.fromStrings("1", "3", "11");
         ColumnVector expected = ColumnVector.fromBoxedBooleans(true, false, false, true, false);
         ColumnVector result = columnVector.contains(cv0)) {
      assertColumnsAreEqual(expected, result);
    }
  }

  @Test
  void testStringOpsEmpty() {
      try (ColumnVector sv = ColumnVector.fromStrings("a", "B", "cd", null, "");
           Scalar emptyString = Scalar.fromString("");
           ColumnVector found = sv.stringContains(emptyString);
           ColumnVector expected = ColumnVector.fromBoxedBooleans(true, true, true, null, true)) {
          assertColumnsAreEqual(found, expected);
      }
      try (ColumnVector sv = ColumnVector.fromStrings("a", "B", "cd", null, "");
           Scalar emptyString = Scalar.fromString("");
           ColumnVector found = sv.startsWith(emptyString);
           ColumnVector expected = ColumnVector.fromBoxedBooleans(true, true, true, null, true)) {
          assertColumnsAreEqual(found, expected);
      }
      try (ColumnVector sv = ColumnVector.fromStrings("a", "B", "cd", null, "");
           Scalar emptyString = Scalar.fromString("");
           ColumnVector found = sv.endsWith(emptyString);
           ColumnVector expected = ColumnVector.fromBoxedBooleans(true, true, true, null, true)) {
          assertColumnsAreEqual(found, expected);
      }
      try (ColumnVector sv = ColumnVector.fromStrings("Héllo", "thésé", null, "ARé", "tést strings");
           Scalar emptyString = Scalar.fromString("");
           ColumnVector found = sv.stringLocate(emptyString, 0, -1);
           ColumnVector expected = ColumnVector.fromBoxedInts(0, 0, null, 0, 0)) {
          assertColumnsAreEqual(found, expected);
      }
  }

  @Test
  void testStringFindOperations() {
    try (ColumnVector testStrings = ColumnVector.fromStrings("", null, "abCD", "1a\"\u0100B1", "a\"\u0100B1", "1a\"\u0100B",
                                      "1a\"\u0100B1\n\t\'", "1a\"\u0100B1\u0453\u1322\u5112", "1a\"\u0100B1Fg26",
                                      "1a\"\u0100B1\\\"\r1a\"\u0100B1", "1a\"\u0100B1\u0498\u1321\u51091a\"\u0100B1",
                                      "1a\"\u0100B1H2O11a\"\u0100B1", "1a\"\u0100B1\\\"\r1a\"\u0100B1",
                                      "\n\t\'1a\"\u0100B1", "\u0453\u1322\u51121a\"\u0100B1", "Fg261a\"\u0100B1");
         ColumnVector emptyStrings = ColumnVector.fromStrings();
         Scalar patternString = Scalar.fromString("1a\"\u0100B1");
         ColumnVector startsResult = testStrings.startsWith(patternString);
         ColumnVector endsResult = testStrings.endsWith(patternString);
         ColumnVector containsResult = testStrings.stringContains(patternString);
         ColumnVector expectedStarts = ColumnVector.fromBoxedBooleans(false, null, false, true, false,
                                                                      false, true, true, true, true, true,
                                                                      true, true, false, false, false);
         ColumnVector expectedEnds = ColumnVector.fromBoxedBooleans(false, null, false, true, false,
                                                                    false, false, false, false, true, true,
                                                                    true, true, true, true, true);
         ColumnVector expectedContains = ColumnVector.fromBoxedBooleans(false, null, false, true, false, false,
                                                                        true, true, true, true, true,
                                                                        true, true, true, true, true);
         ColumnVector startsEmpty = emptyStrings.startsWith(patternString);
         ColumnVector endsEmpty = emptyStrings.endsWith(patternString);
         ColumnVector containsEmpty = emptyStrings.stringContains(patternString);
         ColumnVector expectedEmpty = ColumnVector.fromBoxedBooleans()) {
      assertColumnsAreEqual(startsResult, expectedStarts);
      assertColumnsAreEqual(endsResult, expectedEnds);
      assertColumnsAreEqual(expectedContains, containsResult);
      assertColumnsAreEqual(startsEmpty, expectedEmpty);
      assertColumnsAreEqual(endsEmpty, expectedEmpty);
      assertColumnsAreEqual(expectedEmpty, containsEmpty);
    }
  }

    @Test
    void testExtractRe() {
        try (ColumnVector input = ColumnVector.fromStrings("a1", "b2", "c3", null);
             Table expected = new Table.TestBuilder()
                     .column("a", "b", null, null)
                     .column("1", "2", null, null)
                     .build();
             Table found = input.extractRe("([ab])(\\d)")) {
            assertTablesAreEqual(expected, found);
        }
    }

  @Test
  void testMatchesRe() {
    String patternString1 = "\\d+";
    String patternString2 = "[A-Za-z]+\\s@[A-Za-z]+";
    String patternString3 = ".*";
    String patternString4 = "";
    try (ColumnVector testStrings = ColumnVector.fromStrings("", null, "abCD", "ovér the",
          "lazy @dog", "1234", "00:0:00");
         ColumnVector res1 = testStrings.matchesRe(patternString1);
         ColumnVector res2 = testStrings.matchesRe(patternString2);
         ColumnVector res3 = testStrings.matchesRe(patternString3);
         ColumnVector expected1 = ColumnVector.fromBoxedBooleans(false, null, false, false, false,
           true, true);
         ColumnVector expected2 = ColumnVector.fromBoxedBooleans(false, null, false, false, true,
           false, false);
         ColumnVector expected3 = ColumnVector.fromBoxedBooleans(true, null, true, true, true,
           true, true)) {
      assertColumnsAreEqual(expected1, res1);
      assertColumnsAreEqual(expected2, res2);
      assertColumnsAreEqual(expected3, res3);
    }
    assertThrows(AssertionError.class, () -> {
      try (ColumnVector testStrings = ColumnVector.fromStrings("", null, "abCD", "ovér the",
             "lazy @dog", "1234", "00:0:00");
           ColumnVector res = testStrings.matchesRe(patternString4)) {}
    });
  }

  @Test
  void testContainsRe() {
    String patternString1 = "\\d+";
    String patternString2 = "[A-Za-z]+\\s@[A-Za-z]+";
    String patternString3 = ".*";
    String patternString4 = "";
    try (ColumnVector testStrings = ColumnVector.fromStrings(null, "abCD", "ovér the",
        "lazy @dog", "1234", "00:0:00", "abc1234abc", "there @are 2 lazy @dogs");
         ColumnVector res1 = testStrings.containsRe(patternString1);
         ColumnVector res2 = testStrings.containsRe(patternString2);
         ColumnVector res3 = testStrings.containsRe(patternString3);
         ColumnVector expected1 = ColumnVector.fromBoxedBooleans(null, false, false, false,
             true, true, true, true);
         ColumnVector expected2 = ColumnVector.fromBoxedBooleans(null, false, false, true,
             false, false, false, true);
         ColumnVector expected3 = ColumnVector.fromBoxedBooleans(null, true, true, true,
             true, true, true, true)) {
      assertColumnsAreEqual(expected1, res1);
      assertColumnsAreEqual(expected2, res2);
      assertColumnsAreEqual(expected3, res3);
    }
    assertThrows(AssertionError.class, () -> {
      try (ColumnVector testStrings = ColumnVector.fromStrings("", null, "abCD", "ovér the",
          "lazy @dog", "1234", "00:0:00", "abc1234abc", "there @are 2 lazy @dogs");
           ColumnVector res = testStrings.containsRe(patternString4)) {}
    });
  }

  @Test
  @Disabled("Needs fix for https://github.com/rapidsai/cudf/issues/4671")
  void testContainsReEmptyInput() {
    String patternString1 = ".*";
    try (ColumnVector testStrings = ColumnVector.fromStrings("");
         ColumnVector res1 = testStrings.containsRe(patternString1);
         ColumnVector expected1 = ColumnVector.fromBoxedBooleans(true)) {
      assertColumnsAreEqual(expected1, res1);
    }
  }

  @Test
  void testUrlDecode() {
    String[] inputs = new String[] {
        "foobar.site%2Fq%3Fx%3D%C3%A9%25",
        "a%2Bb%2Dc%2Ad%2Fe",
        "1%092%0A3",
        "abc%401%2523",
        "abc123",
        " %09%0D%0A%0C",
        "",
        null
    };
    String[] expectedOutputs = new String[] {
        "foobar.site/q?x=é%",
        "a+b-c*d/e",
        "1\t2\n3",
        "abc@1%23",
        "abc123",
        " \t\r\n\f",
        "",
        null
    };

    try (ColumnVector v = ColumnVector.fromStrings(inputs);
         ColumnVector expected = ColumnVector.fromStrings(expectedOutputs);
         ColumnVector actual = v.urlDecode()) {
      assertColumnsAreEqual(expected, actual);
    }
  }

  @Test
  void testUrlEncode() {
    String[] inputs = new String[] {
        "foobar.site/q?x=é%",
        "a+b-c*d/e",
        "1\t2\n3",
        "abc@1%23",
        "abc123",
        " \t\r\n\f",
        "",
        null
    };
    String[] expectedOutputs = new String[] {
        "foobar.site%2Fq%3Fx%3D%C3%A9%25",
        "a%2Bb-c%2Ad%2Fe",
        "1%092%0A3",
        "abc%401%2523",
        "abc123",
        "%20%09%0D%0A%0C",
        "",
        null
    };

    try (ColumnVector v = ColumnVector.fromStrings(inputs);
         ColumnVector expected = ColumnVector.fromStrings(expectedOutputs);
         ColumnVector actual = v.urlEncode()) {
      assertColumnsAreEqual(expected, actual);
    }
  }

  @Test
  void testStringFindOperationsThrowsException() {
    assertThrows(CudfException.class, () -> {
      try (ColumnVector sv = ColumnVector.fromStrings("a", "B", "cd");
           Scalar emptyString = Scalar.fromString(null);
           ColumnVector concat = sv.startsWith(emptyString)) {}
    });
    assertThrows(CudfException.class, () -> {
      try (ColumnVector sv = ColumnVector.fromStrings("a", "B", "cd");
           Scalar emptyString = Scalar.fromString(null);
           ColumnVector concat = sv.endsWith(emptyString)) {}
    });
    assertThrows(CudfException.class, () -> {
      try (ColumnVector sv = ColumnVector.fromStrings("a", "B", "cd");
           Scalar emptyString = Scalar.fromString(null);
           ColumnVector concat = sv.stringContains(emptyString)) {}
    });
    assertThrows(AssertionError.class, () -> {
      try (ColumnVector sv = ColumnVector.fromStrings("a", "B", "cd");
           ColumnVector concat = sv.startsWith(null)) {}
    });
    assertThrows(AssertionError.class, () -> {
      try (ColumnVector sv = ColumnVector.fromStrings("a", "B", "cd");
           ColumnVector concat = sv.endsWith(null)) {}
    });
    assertThrows(AssertionError.class, () -> {
      try (ColumnVector sv = ColumnVector.fromStrings("a", "B", "cd");
           Scalar intScalar = Scalar.fromInt(1);
           ColumnVector concat = sv.startsWith(intScalar)) {}
    });
    assertThrows(AssertionError.class, () -> {
      try (ColumnVector sv = ColumnVector.fromStrings("a", "B", "cd");
           Scalar intScalar = Scalar.fromInt(1);
           ColumnVector concat = sv.endsWith(intScalar)) {}
    });
    assertThrows(AssertionError.class, () -> {
      try (ColumnVector sv = ColumnVector.fromStrings("a", "B", "cd");
           Scalar intScalar = Scalar.fromInt(1);
           ColumnVector concat = sv.stringContains(intScalar)) {}
    });
    assertThrows(AssertionError.class, () -> {
      try (ColumnVector v = ColumnVector.fromInts(1, 43, 42, 11, 2);
           Scalar patternString = Scalar.fromString("a");
           ColumnVector concat = v.startsWith(patternString)) {}
    });
    assertThrows(AssertionError.class, () -> {
      try (ColumnVector v = ColumnVector.fromInts(1, 43, 42, 11, 2);
           Scalar patternString = Scalar.fromString("a");
           ColumnVector concat = v.endsWith(patternString)) {}
    });
    assertThrows(AssertionError.class, () -> {
      try (ColumnVector v = ColumnVector.fromInts(1, 43, 42, 11, 2);
           Scalar patternString = Scalar.fromString("a");
           ColumnVector concat = v.stringContains(patternString)) {}
    });
  }

  @Test
  void testStringLocate() {
    try(ColumnVector v = ColumnVector.fromStrings("Héllo", "thésé", null, "\r\ud720é\ud721", "ARé",
                                                  "\\THE\t8\ud720", "tést strings", "", "éé");
        ColumnVector e_locate1 = ColumnVector.fromBoxedInts(1, 2, null, 2, 2, -1, 1, -1, 0);
        ColumnVector e_locate2 = ColumnVector.fromBoxedInts(-1, 2, null, -1, -1, -1, 1, -1, -1);
        ColumnVector e_locate3 = ColumnVector.fromBoxedInts(-1, -1, null, 1, -1, 6, -1, -1, -1);
        Scalar pattern1 = Scalar.fromString("é");
        Scalar pattern2 = Scalar.fromString("és");
        Scalar pattern3 = Scalar.fromString("\ud720");
        ColumnVector locate1 = v.stringLocate(pattern1, 0, -1);
        ColumnVector locate2 = v.stringLocate(pattern2, 0, -1);
        ColumnVector locate3 = v.stringLocate(pattern3, 0, -1)) {
      assertColumnsAreEqual(locate1, e_locate1);
      assertColumnsAreEqual(locate2, e_locate2);
      assertColumnsAreEqual(locate3, e_locate3);
    }
  }

  @Test
  void testStringLocateOffsets() {
    try(ColumnVector v = ColumnVector.fromStrings("Héllo", "thésé", null, "\r\ud720é\ud721", "ARé",
                                                  "\\THE\t8\ud720", "tést strings", "", "éé");
        Scalar pattern = Scalar.fromString("é");
        ColumnVector e_empty = ColumnVector.fromBoxedInts(-1, -1, null, -1, -1, -1, -1, -1, -1);
        ColumnVector e_start = ColumnVector.fromBoxedInts(-1, 2, null, 2, 2, -1, -1, -1, -1);
        ColumnVector e_end = ColumnVector.fromBoxedInts(1, -1, null, -1, -1, -1, 1, -1, 0);
        ColumnVector locate_empty = v.stringLocate(pattern, 13, -1);
        ColumnVector locate_start = v.stringLocate(pattern, 2, -1);
        ColumnVector locate_end = v.stringLocate(pattern, 0, 2)) {
      assertColumnsAreEqual(locate_empty, e_empty);
      assertColumnsAreEqual(locate_start, e_start);
      assertColumnsAreEqual(locate_end, e_end);
    }
  }

  @Test
  void testStringLocateThrowsException() {
    assertThrows(AssertionError.class, () -> {
      try (ColumnVector cv = ColumnVector.fromStrings("Héllo", "thésé", null, "ARé", "tést strings");
           ColumnVector locate = cv.stringLocate(null, 0, -1)) {}
    });
    assertThrows(CudfException.class, () -> {
      try (ColumnVector cv = ColumnVector.fromStrings("Héllo", "thésé", null, "ARé", "tést strings");
           Scalar pattern = Scalar.fromString(null);
           ColumnVector locate = cv.stringLocate(pattern, 0, -1)) {}
    });
    assertThrows(AssertionError.class, () -> {
      try (ColumnVector cv = ColumnVector.fromStrings("Héllo", "thésé", null, "ARé", "tést strings");
           Scalar intScalar = Scalar.fromInt(1);
           ColumnVector locate = cv.stringLocate(intScalar, 0, -1)) {}
    });
    assertThrows(AssertionError.class, () -> {
      try (ColumnVector cv = ColumnVector.fromStrings("Héllo", "thésé", null, "ARé", "tést strings");
           Scalar pattern = Scalar.fromString("é");
           ColumnVector locate = cv.stringLocate(pattern, -2, -1)) {}
    });
    assertThrows(AssertionError.class, () -> {
      try (ColumnVector cv = ColumnVector.fromStrings("Héllo", "thésé", null, "ARé", "tést strings");
           Scalar pattern = Scalar.fromString("é");
           ColumnVector locate = cv.stringLocate(pattern, 2, 1)) {}
    });
    assertThrows(AssertionError.class, () -> {
      try (ColumnVector cv = ColumnVector.fromInts(1, 43, 42, 11, 2);
           Scalar pattern = Scalar.fromString("é");
           ColumnVector concat = cv.stringLocate(pattern, 0, -1)) {}
    });
  }

  @Test
  void testsubstring() {
    try (ColumnVector v = ColumnVector.fromStrings("Héllo", "thésé", null,"", "ARé", "strings");
         ColumnVector e_allParameters = ColumnVector.fromStrings("llo", "ésé", null, "", "é", "rin");
         ColumnVector e_withoutStop = ColumnVector.fromStrings("llo", "ésé", null, "", "é", "rings");
         ColumnVector substring_allParam = v.substring(2, 5);
         ColumnVector substring_NoEnd = v.substring(2)) {
      assertColumnsAreEqual(e_allParameters, substring_allParam);
      assertColumnsAreEqual(e_withoutStop, substring_NoEnd);
    }
  }

  @Test
  void testExtractListElements() {
      try (ColumnVector v = ColumnVector.fromStrings("Héllo there", "thésé", null, "", "ARé some", "test strings");
           ColumnVector expected = ColumnVector.fromStrings("Héllo",
                   "thésé",
                   null,
                   null,
                   "ARé",
                   "test");
           ColumnVector tmp = v.stringSplitRecord();
           ColumnVector result = tmp.extractListElement(0)) {
          assertColumnsAreEqual(expected, result);
      }
  }

  @Test
  void testListContainsString() {
    List<String> list1 = Arrays.asList("Héllo there", "thésé");
    List<String> list2 = Arrays.asList("", "ARé some", "test strings");
    List<String> list3 = Arrays.asList(null, "", "ARé some", "test strings", "thésé");
    List<String> list4 = Arrays.asList(null, "", "ARé some", "test strings");
    List<String> list5 = null;
    try (ColumnVector v = ColumnVector.fromLists(new HostColumnVector.ListType(true,
        new HostColumnVector.BasicType(true, DType.STRING)), list1, list2, list3, list4, list5);
         ColumnVector expected = ColumnVector.fromBoxedBooleans(true, false, true, null, null);
         Scalar strScalar = Scalar.fromString("thésé");
         ColumnVector result = v.listContains(strScalar)) {
      assertColumnsAreEqual(expected, result);
    }
  }

  @Test
  void testListContainsInt() {
    List<Integer> list1 = Arrays.asList(1, 2, 3);
    List<Integer> list2 = Arrays.asList(4, 5, 6);
    List<Integer> list3 = Arrays.asList(7, 8, 9);
    List<Integer> list4 = null;
    try (ColumnVector v = ColumnVector.fromLists(new HostColumnVector.ListType(true,
        new HostColumnVector.BasicType(true, DType.INT32)), list1, list2, list3, list4);
         ColumnVector expected = ColumnVector.fromBoxedBooleans(false, false, true, null);
         Scalar intScalar = Scalar.fromInt(7);
         ColumnVector result = v.listContains(intScalar)) {
      assertColumnsAreEqual(expected, result);
    }
  }

  @Test
  void testListContainsStringCol() {
    List<String> list1 = Arrays.asList("Héllo there", "thésé");
    List<String> list2 = Arrays.asList("", "ARé some", "test strings");
    List<String> list3 = Arrays.asList("FOO", "", "ARé some", "test");
    List<String> list4 = Arrays.asList(null, "FOO", "", "ARé some", "test");
    List<String> list5 = Arrays.asList(null, "FOO", "", "ARé some", "test");
    List<String> list6 = null;
    try (ColumnVector v = ColumnVector.fromLists(new HostColumnVector.ListType(true,
        new HostColumnVector.BasicType(true, DType.STRING)), list1, list2, list3, list4, list5, list6);
         ColumnVector expected = ColumnVector.fromBoxedBooleans(true, true, true, true, null, null);
         ColumnVector strCol = ColumnVector.fromStrings("thésé", "", "test", "test", "iotA", null);
         ColumnVector result = v.listContainsColumn(strCol)) {
      assertColumnsAreEqual(expected, result);
    }
  }

  @Test
  void testListContainsIntCol() {
    List<Integer> list1 = Arrays.asList(1, 2, 3);
    List<Integer> list2 = Arrays.asList(4, 5, 6);
    List<Integer> list3 = Arrays.asList(null, 8, 9);
    List<Integer> list4 = Arrays.asList(null, 8, 9);
    List<Integer> list5 = null;
    try (ColumnVector v = ColumnVector.fromLists(new HostColumnVector.ListType(true,
        new HostColumnVector.BasicType(true, DType.INT32)), list1, list2, list3, list4, list5);
         ColumnVector expected = ColumnVector.fromBoxedBooleans(true, false, true, null, null);
         ColumnVector intCol = ColumnVector.fromBoxedInts(3, 3, 8, 3, null);
         ColumnVector result = v.listContainsColumn(intCol)) {
      assertColumnsAreEqual(expected, result);
    }
  }

  @Test
  void testListSortRowsWithIntChild() {
    List<Integer> list1 = Arrays.asList(1, 3, 0, 2);
    List<Integer> ascSortedList1 = Arrays.asList(0, 1, 2, 3);
    List<Integer> decSortedList1 = Arrays.asList(3, 2, 1, 0);

    List<Integer> list2 = Arrays.asList(7, 5, 6, 4);
    List<Integer> ascSortedList2 = Arrays.asList(4, 5, 6, 7);
    List<Integer> decSortedList2 = Arrays.asList(7, 6, 5, 4);

    List<Integer> list3 = Arrays.asList(-8, null, -9, -10);
    List<Integer> ascSortedList3 = Arrays.asList(-10, -9, -8, null);
    List<Integer> ascSortedNullMinList3 = Arrays.asList(null, -10, -9, -8);
    List<Integer> decSortedList3 = Arrays.asList(null, -8, -9, -10);
    List<Integer> decSortedNullMinList3 = Arrays.asList(-8, -9, -10, null);

    List<Integer> list4 = Arrays.asList(null, -12, null, 11);
    List<Integer> ascSortedList4 = Arrays.asList(-12, 11, null, null);
    List<Integer> ascSortedNullMinList4 = Arrays.asList(null, null, -12, 11);
    List<Integer> decSortedList4 = Arrays.asList(null, null, 11, -12);
    List<Integer> decSortedNullMinList4 = Arrays.asList(11, -12, null, null);

    List<Integer> list5 = null;

    HostColumnVector.ListType listType = new HostColumnVector.ListType(true,
        new HostColumnVector.BasicType(true, DType.INT32));
    // Ascending + NullLargest
    try (ColumnVector v = ColumnVector.fromLists(listType, list1, list2, list3, list4, list5);
         ColumnVector expected = ColumnVector.fromLists(listType,
             ascSortedList1, ascSortedList2, ascSortedList3, ascSortedList4, list5);
         ColumnVector result = v.listSortRows(false, false)) {
      assertColumnsAreEqual(expected, result);
    }
    // Descending + NullLargest
    try (ColumnVector v = ColumnVector.fromLists(listType, list1, list2, list3, list4, list5);
         ColumnVector expected = ColumnVector.fromLists(listType,
             decSortedList1, decSortedList2, decSortedList3, decSortedList4, list5);
         ColumnVector result = v.listSortRows(true, false)) {
      assertColumnsAreEqual(expected, result);
    }
    // Ascending + NullSmallest
    try (ColumnVector v = ColumnVector.fromLists(listType, list1, list2, list3, list4, list5);
         ColumnVector expected = ColumnVector.fromLists(listType,
             ascSortedList1, ascSortedList2, ascSortedNullMinList3, ascSortedNullMinList4, list5);
         ColumnVector result = v.listSortRows(false, true)) {
      assertColumnsAreEqual(expected, result);
    }
    // Descending + NullSmallest
    try (ColumnVector v = ColumnVector.fromLists(listType, list1, list2, list3, list4, list5);
         ColumnVector expected = ColumnVector.fromLists(listType,
             decSortedList1, decSortedList2, decSortedNullMinList3, decSortedNullMinList4, list5);
         ColumnVector result = v.listSortRows(true, true)) {
      assertColumnsAreEqual(expected, result);
    }
  }

  @Test
  void testListSortRowsWithStringChild() {
    List<String> list1 = Arrays.asList("b", "d", "a", "c");
    List<String> ascSortedList1 = Arrays.asList("a", "b", "c", "d");
    List<String> decSortedList1 = Arrays.asList("d", "c", "b", "a");

    List<String> list2 = Arrays.asList("h", "f", "g", "e");
    List<String> ascSortedList2 = Arrays.asList("e", "f", "g", "h");
    List<String> decSortedList2 = Arrays.asList("h", "g", "f", "e");

    List<String> list3 = Arrays.asList("C", null, "B", "A");
    List<String> ascSortedList3 = Arrays.asList("A", "B", "C", null);
    List<String> ascSortedNullMinList3 = Arrays.asList(null, "A", "B", "C");
    List<String> decSortedList3 = Arrays.asList(null, "C", "B", "A");
    List<String> decSortedNullMinList3 = Arrays.asList("C", "B", "A", null);

    List<String> list4 = Arrays.asList(null, "D", null, "d");
    List<String> ascSortedList4 = Arrays.asList("D", "d", null, null);
    List<String> ascSortedNullMinList4 = Arrays.asList(null, null, "D", "d");
    List<String> decSortedList4 = Arrays.asList(null, null, "d", "D");
    List<String> decSortedNullMinList4 = Arrays.asList("d", "D", null, null);

    List<String> list5 = null;

    HostColumnVector.ListType listType = new HostColumnVector.ListType(true,
        new HostColumnVector.BasicType(true, DType.STRING));
    // Ascending + NullLargest
    try (ColumnVector v = ColumnVector.fromLists(listType, list1, list2, list3, list4, list5);
         ColumnVector expected = ColumnVector.fromLists(listType,
             ascSortedList1, ascSortedList2, ascSortedList3, ascSortedList4, list5);
         ColumnVector result = v.listSortRows(false, false)) {
      assertColumnsAreEqual(expected, result);
    }
    // Descending + NullLargest
    try (ColumnVector v = ColumnVector.fromLists(listType, list1, list2, list3, list4, list5);
         ColumnVector expected = ColumnVector.fromLists(listType,
             decSortedList1, decSortedList2, decSortedList3, decSortedList4, list5);
         ColumnVector result = v.listSortRows(true, false)) {
      assertColumnsAreEqual(expected, result);
    }
    // Ascending + NullSmallest
    try (ColumnVector v = ColumnVector.fromLists(listType, list1, list2, list3, list4, list5);
         ColumnVector expected = ColumnVector.fromLists(listType,
             ascSortedList1, ascSortedList2, ascSortedNullMinList3, ascSortedNullMinList4, list5);
         ColumnVector result = v.listSortRows(false, true)) {
      assertColumnsAreEqual(expected, result);
    }
    // Descending + NullSmallest
    try (ColumnVector v = ColumnVector.fromLists(listType, list1, list2, list3, list4, list5);
         ColumnVector expected = ColumnVector.fromLists(listType,
             decSortedList1, decSortedList2, decSortedNullMinList3, decSortedNullMinList4, list5);
         ColumnVector result = v.listSortRows(true, true)) {
      assertColumnsAreEqual(expected, result);
    }
  }

  @Test
  void testStringSplitRecord() {
      try (ColumnVector v = ColumnVector.fromStrings("Héllo there", "thésé", "null", "", "ARé some", "test strings");
           ColumnVector expected = ColumnVector.fromLists(
                   new HostColumnVector.ListType(true,
                       new HostColumnVector.BasicType(true, DType.STRING)),
                   Arrays.asList("Héllo", "there"),
                   Arrays.asList("thésé"),
                   Arrays.asList("null"),
                   Arrays.asList(""),
                   Arrays.asList("ARé", "some"),
                   Arrays.asList("test", "strings"));
           Scalar pattern = Scalar.fromString(" ");
           ColumnVector result = v.stringSplitRecord(pattern, -1)) {
          assertColumnsAreEqual(expected, result);
      }
  }

  @Test
  void testStringSplit() {
    try (ColumnVector v = ColumnVector.fromStrings("Héllo there", "thésé", null, "", "ARé some", "test strings");
         Table expected = new Table.TestBuilder().column("Héllo", "thésé", null, "", "ARé", "test")
         .column("there", null, null, null, "some", "strings")
         .build();
         Scalar pattern = Scalar.fromString(" ");
         Table result = v.stringSplit(pattern)) {
      assertTablesAreEqual(expected, result);
    }
  }

  @Test
  void teststringSplitWhiteSpace() {
    try (ColumnVector v = ColumnVector.fromStrings("Héllo thesé", null, "are\tsome", "tést\nString", " ");
         Table expected = new Table.TestBuilder().column("Héllo", null, "are", "tést", null)
         .column("thesé", null, "some", "String", null)
         .build();
         Table result = v.stringSplit()) {
      assertTablesAreEqual(expected, result);
    }
  }

  @Test
  void teststringSplitThrowsException() {
    assertThrows(CudfException.class, () -> {
      try (ColumnVector cv = ColumnVector.fromStrings("Héllo", "thésé", null, "", "ARé", "strings");
           Scalar delimiter = Scalar.fromString(null);
           Table result = cv.stringSplit(delimiter)) {}
    });
    assertThrows(AssertionError.class, () -> {
    try (ColumnVector cv = ColumnVector.fromStrings("Héllo", "thésé", null, "", "ARé", "strings");
         Scalar delimiter = Scalar.fromInt(1);
         Table result = cv.stringSplit(delimiter)) {}
    });
    assertThrows(AssertionError.class, () -> {
      try (ColumnVector cv = ColumnVector.fromStrings("Héllo", "thésé", null, "", "ARé", "strings");
           Table result = cv.stringSplit(null)) {}
    });
  }

  @Test
  void testsubstringColumn() {
    try (ColumnVector v = ColumnVector.fromStrings("Héllo", "thésé", null, "", "ARé", "strings");
         ColumnVector start = ColumnVector.fromInts(2, 1, 1, 1, 0, 1);
         ColumnVector end = ColumnVector.fromInts(5, 3, 1, 1, -1, -1);
         ColumnVector expected = ColumnVector.fromStrings("llo", "hé", null, "", "ARé", "trings");
         ColumnVector result = v.substring(start, end)) {
      assertColumnsAreEqual(expected, result);
    }
  }

  @Test
  void testsubstringThrowsException() {
    assertThrows(AssertionError.class, () -> {
      try (ColumnVector v = ColumnVector.fromStrings("Héllo", "thésé", null, "", "ARé", "strings");
           ColumnVector start = ColumnVector.fromInts(2, 1, 1, 1, 0, 1);
           ColumnVector end = ColumnVector.fromInts(5, 3, 1, 1, -1);
           ColumnVector substring = v.substring(start, end)) {
      }
    });
  }

  @Test
  void teststringReplace() {
    try (ColumnVector v = ColumnVector.fromStrings("Héllo", "thésssé", null, "", "ARé", "sssstrings");
         ColumnVector e_allParameters = ColumnVector.fromStrings("Héllo", "théSsé", null, "", "ARé", "SStrings");
         Scalar target = Scalar.fromString("ss");
         Scalar replace = Scalar.fromString("S");
         ColumnVector replace_allParameters = v.stringReplace(target, replace)) {
      assertColumnsAreEqual(e_allParameters, replace_allParameters);
    }
  }

  @Test
  void teststringReplaceThrowsException() {
    assertThrows(AssertionError.class, () -> {
      try (ColumnVector testStrings = ColumnVector.fromStrings("Héllo", "thésé", null, "", "ARé", "strings");
           Scalar target= Scalar.fromString("");
           Scalar replace=Scalar.fromString("a");
           ColumnVector result = testStrings.stringReplace(target,replace)){}
    });
  }

  @Test
  void testStringReplaceWithBackrefs() {

    try (ColumnVector v = ColumnVector.fromStrings("<h1>title</h1>", "<h1>another title</h1>",
        null);
         ColumnVector expected = ColumnVector.fromStrings("<h2>title</h2>",
             "<h2>another title</h2>", null);
         ColumnVector actual = v.stringReplaceWithBackrefs("<h1>(.*)</h1>", "<h2>\\1</h2>")) {
      assertColumnsAreEqual(expected, actual);
    }

    try (ColumnVector v = ColumnVector.fromStrings("2020-1-01", "2020-2-02", null);
         ColumnVector expected = ColumnVector.fromStrings("2020-01-01", "2020-02-02", null);
         ColumnVector actual = v.stringReplaceWithBackrefs("-([0-9])-", "-0\\1-")) {
      assertColumnsAreEqual(expected, actual);
    }

    try (ColumnVector v = ColumnVector.fromStrings("2020-01-1", "2020-02-2",
        "2020-03-3invalid", null);
         ColumnVector expected = ColumnVector.fromStrings("2020-01-01", "2020-02-02",
             "2020-03-3invalid", null);
         ColumnVector actual = v.stringReplaceWithBackrefs(
             "-([0-9])$", "-0\\1")) {
      assertColumnsAreEqual(expected, actual);
    }

    try (ColumnVector v = ColumnVector.fromStrings("2020-01-1 random_text", "2020-02-2T12:34:56",
        "2020-03-3invalid", null);
         ColumnVector expected = ColumnVector.fromStrings("2020-01-01 random_text",
             "2020-02-02T12:34:56", "2020-03-3invalid", null);
         ColumnVector actual = v.stringReplaceWithBackrefs(
             "-([0-9])([ T])", "-0\\1\\2")) {
      assertColumnsAreEqual(expected, actual);
    }

  }

  @Test
  void testLPad() {
      try (ColumnVector v = ColumnVector.fromStrings("1", "23", "45678", null);
           ColumnVector expected = ColumnVector.fromStrings("A1", "23", "45678", null);
           ColumnVector actual = v.pad(2, PadSide.LEFT, "A")) {
          assertColumnsAreEqual(expected, actual);
      }
      try (ColumnVector v = ColumnVector.fromStrings("1", "23", "45678", null);
           ColumnVector expected = ColumnVector.fromStrings("___1", "__23", "45678", null);
           ColumnVector actual = v.pad(4, PadSide.LEFT, "_")) {
          assertColumnsAreEqual(expected, actual);
      }
  }

  @Test
  void testRPad() {
      try (ColumnVector v = ColumnVector.fromStrings("1", "23", "45678", null);
           ColumnVector expected = ColumnVector.fromStrings("1A", "23", "45678", null);
           ColumnVector actual = v.pad(2, PadSide.RIGHT, "A")) {
          assertColumnsAreEqual(expected, actual);
      }
      try (ColumnVector v = ColumnVector.fromStrings("1", "23", "45678", null);
           ColumnVector expected = ColumnVector.fromStrings("1___", "23__", "45678", null);
           ColumnVector actual = v.pad(4, PadSide.RIGHT, "_")) {
          assertColumnsAreEqual(expected, actual);
      }
  }

  @Test
  void testPad() {
      try (ColumnVector v = ColumnVector.fromStrings("1", "23", "45678", null);
           ColumnVector expected = ColumnVector.fromStrings("1A", "23", "45678", null);
           ColumnVector actual = v.pad(2, PadSide.BOTH, "A")) {
          assertColumnsAreEqual(expected, actual);
      }
      try (ColumnVector v = ColumnVector.fromStrings("1", "23", "45678", null);
           ColumnVector expected = ColumnVector.fromStrings("_1__", "_23_", "45678", null);
           ColumnVector actual = v.pad(4, PadSide.BOTH, "_")) {
          assertColumnsAreEqual(expected, actual);
      }
  }

  @Test
  void testZfill() {
    try (ColumnVector v = ColumnVector.fromStrings("1", "23", "45678", null);
         ColumnVector expected = ColumnVector.fromStrings("01", "23", "45678", null);
         ColumnVector actual = v.zfill(2)) {
      assertColumnsAreEqual(expected, actual);
    }
    try (ColumnVector v = ColumnVector.fromStrings("1", "23", "45678", null);
         ColumnVector expected = ColumnVector.fromStrings("0001", "0023", "45678", null);
         ColumnVector actual = v.zfill(4)) {
      assertColumnsAreEqual(expected, actual);
    }
  }

  @Test
  void testStringTitlize() {
    try (ColumnVector cv = ColumnVector.fromStrings("sPark", "sqL", "lowercase", null, "", "UPPERCASE");
         ColumnVector result = cv.toTitle();
         ColumnVector expected = ColumnVector.fromStrings("Spark", "Sql", "Lowercase", null, "", "Uppercase")) {
      assertColumnsAreEqual(expected, result);
    }
  }

  @Test
  void testStringCapitalize() {
    try (ColumnVector cv = ColumnVector.fromStrings("s Park", "S\nqL", "lower \tcase",
                                                    null, "", "UPPER\rCASE")) {
      try (Scalar deli = Scalar.fromString("");
           ColumnVector result = cv.capitalize(deli);
           ColumnVector expected = ColumnVector.fromStrings("S park", "S\nql", "Lower \tcase",
                                                            null, "", "Upper\rcase")) {
        assertColumnsAreEqual(expected, result);
      }
      try (Scalar deli = Scalar.fromString(" ");
           ColumnVector result = cv.capitalize(deli);
           ColumnVector expected = ColumnVector.fromStrings("S Park", "S\nql", "Lower \tcase",
                                                            null, "", "Upper\rcase")) {
        assertColumnsAreEqual(expected, result);
      }
      try (Scalar deli = Scalar.fromString(" \t\n");
           ColumnVector result = cv.capitalize(deli);
           ColumnVector expected = ColumnVector.fromStrings("S Park", "S\nQl", "Lower \tCase",
                                                             null, "", "Upper\rcase")) {
        assertColumnsAreEqual(expected, result);
      }
    }
  }

  @Test
  void testNansToNulls() {
    Float[] floats = new Float[]{1.2f, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, null,
        Float.NaN, Float.MAX_VALUE, Float.MIN_VALUE, 435243.2323f, POSITIVE_FLOAT_NAN_LOWER_RANGE,
        POSITIVE_FLOAT_NAN_UPPER_RANGE, NEGATIVE_FLOAT_NAN_LOWER_RANGE,
        NEGATIVE_FLOAT_NAN_UPPER_RANGE};

    Float[] expectedFloats = new Float[]{1.2f, Float.POSITIVE_INFINITY,
        Float.NEGATIVE_INFINITY, null, null, Float.MAX_VALUE, Float.MIN_VALUE, 435243.2323f,
        null, null, null, null};

    Double[] doubles = new Double[]{1.2d, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, null,
        Double.NaN, Double.MAX_VALUE, Double.MIN_VALUE, 435243.2323d, POSITIVE_DOUBLE_NAN_LOWER_RANGE,
        POSITIVE_DOUBLE_NAN_UPPER_RANGE, NEGATIVE_DOUBLE_NAN_LOWER_RANGE,
        NEGATIVE_DOUBLE_NAN_UPPER_RANGE};

   Double[] expectedDoubles = new Double[]{1.2d, Double.POSITIVE_INFINITY,
        Double.NEGATIVE_INFINITY, null, null, Double.MAX_VALUE, Double.MIN_VALUE,
        435243.2323d, null, null, null, null};

    try (ColumnVector cvFloat = ColumnVector.fromBoxedFloats(floats);
         ColumnVector cvDouble = ColumnVector.fromBoxedDoubles(doubles);
         ColumnVector resultFloat = cvFloat.nansToNulls();
         ColumnVector resultDouble = cvDouble.nansToNulls();
         ColumnVector expectedFloat = ColumnVector.fromBoxedFloats(expectedFloats);
         ColumnVector expectedDouble = ColumnVector.fromBoxedDoubles(expectedDoubles)) {
      assertColumnsAreEqual(expectedFloat, resultFloat);
      assertColumnsAreEqual(expectedDouble, resultDouble);
    }
  }

  @Test
  void testIsIntegerWithBounds() {
    String[] intStrings = {"A", "nan", "Inf", "-Inf", "3.5",
        String.valueOf(Byte.MIN_VALUE),
        String.valueOf(Byte.MIN_VALUE + 1L),
        String.valueOf(Byte.MIN_VALUE - 1L),
        String.valueOf(Byte.MAX_VALUE),
        String.valueOf(Byte.MAX_VALUE + 1L),
        String.valueOf(Byte.MAX_VALUE - 1L),
        String.valueOf(Short.MIN_VALUE),
        String.valueOf(Short.MIN_VALUE + 1L),
        String.valueOf(Short.MIN_VALUE - 1L),
        String.valueOf(Short.MAX_VALUE),
        String.valueOf(Short.MAX_VALUE + 1L),
        String.valueOf(Short.MAX_VALUE - 1L),
        String.valueOf(Integer.MIN_VALUE),
        String.valueOf(Integer.MIN_VALUE + 1L),
        String.valueOf(Integer.MIN_VALUE - 1L),
        String.valueOf(Integer.MAX_VALUE),
        String.valueOf(Integer.MAX_VALUE + 1L),
        String.valueOf(Integer.MAX_VALUE - 1L),
        String.valueOf(Long.MIN_VALUE),
        String.valueOf(Long.MIN_VALUE + 1L),
        "-9223372036854775809",
        String.valueOf(Long.MAX_VALUE),
        "9223372036854775808",
        String.valueOf(Long.MAX_VALUE - 1L)};
    try (ColumnVector intStringCV = ColumnVector.fromStrings(intStrings);
         ColumnVector isByte = intStringCV.isInteger(DType.INT8);
         ColumnVector expectedByte = ColumnVector.fromBoxedBooleans(
             false, false, false, false, false,
             true, true, false, true, false, true,
             false, false, false, false, false, false,
             false, false, false, false, false, false,
             false, false, false, false, false, false);
         ColumnVector isShort = intStringCV.isInteger(DType.INT16);
         ColumnVector expectedShort = ColumnVector.fromBoxedBooleans(
             false, false, false, false, false,
             true, true, true, true, true, true,
             true, true, false, true, false, true,
             false, false, false, false, false, false,
             false, false, false, false, false, false);
         ColumnVector isInt = intStringCV.isInteger(DType.INT32);
         ColumnVector expectedInt = ColumnVector.fromBoxedBooleans(
             false, false, false, false, false,
             true, true, true, true, true, true,
             true, true, true, true, true, true,
             true, true, false, true, false, true,
             false, false, false, false, false, false);
         ColumnVector isLong = intStringCV.isInteger(DType.INT64);
         ColumnVector expectedLong = ColumnVector.fromBoxedBooleans(
             false, false, false, false, false,
             true, true, true, true, true, true,
             true, true, true, true, true, true,
             true, true, true, true, true, true,
             true, true, false, true, false, true)) {
      assertColumnsAreEqual(expectedByte, isByte);
      assertColumnsAreEqual(expectedShort, isShort);
      assertColumnsAreEqual(expectedInt, isInt);
      assertColumnsAreEqual(expectedLong, isLong);
    }
  }

  @Test
  void testIsInteger() {
    String[] intStrings = {"A", "nan", "Inf", "-Inf", "Infinity", "infinity", "2147483647",
        "2147483648", "-2147483648", "-2147483649", "NULL", "null", null, "1.2", "1.2e-4", "0.00012"};
    String[] longStrings = {"A", "nan", "Inf", "-Inf", "Infinity", "infinity",
        "9223372036854775807", "9223372036854775808", "-9223372036854775808",
        "-9223372036854775809", "NULL", "null", null, "1.2", "1.2e-4", "0.00012"};
    try (ColumnVector intStringCV = ColumnVector.fromStrings(intStrings);
         ColumnVector longStringCV = ColumnVector.fromStrings(longStrings);
         ColumnVector isInt = intStringCV.isInteger();
         ColumnVector isLong = longStringCV.isInteger();
         ColumnVector ints = intStringCV.asInts();
         ColumnVector longs = longStringCV.asLongs();
         ColumnVector expectedInts = ColumnVector.fromBoxedInts(0, 0, 0, 0, 0, 0, Integer.MAX_VALUE,
             Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE, 0, 0, null, 1, 1, 0);
         ColumnVector expectedLongs = ColumnVector.fromBoxedLongs(0l, 0l, 0l, 0l, 0l, 0l, Long.MAX_VALUE,
             Long.MIN_VALUE, Long.MIN_VALUE, Long.MAX_VALUE, 0l, 0l, null, 1l, 1l, 0l);
         ColumnVector expected = ColumnVector.fromBoxedBooleans(false, false, false, false, false,
             false, true, true, true, true, false, false, null, false, false, false)) {
      assertColumnsAreEqual(expected, isInt);
      assertColumnsAreEqual(expected, isLong);
      assertColumnsAreEqual(expectedInts, ints);
      assertColumnsAreEqual(expectedLongs, longs);
    }
  }

  @Test
  void testIsFloat() {
    String[] floatStrings = {"A", "nan", "Inf", "-Inf", "Infinity", "infinity", "-0.0", "0.0",
        "3.4028235E38", "3.4028236E38", "-3.4028235E38", "-3.4028236E38", "1.2e-24", "NULL", "null",
        null, "423"};
    try (ColumnVector floatStringCV = ColumnVector.fromStrings(floatStrings);
         ColumnVector isFloat = floatStringCV.isFloat();
         ColumnVector floats = floatStringCV.asFloats();
         ColumnVector expectedFloats = ColumnVector.fromBoxedFloats(0f, 0f, Float.POSITIVE_INFINITY,
             Float.NEGATIVE_INFINITY, 0f, 0f, -0f, 0f, Float.MAX_VALUE, Float.POSITIVE_INFINITY,
             -Float.MAX_VALUE, Float.NEGATIVE_INFINITY, 1.2e-24f, 0f, 0f, null, 423f);
         ColumnVector expected = ColumnVector.fromBoxedBooleans(false, false, true, true, false,
             false, true, true, true, true, true, true, true, false, false, null, true)) {
      assertColumnsAreEqual(expected, isFloat);
      assertColumnsAreEqual(expectedFloats, floats);
    }
  }

  @Test
  void testIsDouble() {
    String[] doubleStrings = {"A", "nan", "Inf", "-Inf", "Infinity", "infinity", "-0.0", "0.0",
        "1.7976931348623157E308",
        // Current CUDF Code does not detect overflow for this. "1.7976931348623158E308",
        // So we make it a little larger for this test
        "1.7976931348623159E308",
        "-1.7976931348623157E308",
        // Current CUDF Code does not detect overflow for this. "-1.7976931348623158E308",
        // So we make it a little larger for this test
        "-1.7976931348623159E308",
        "1.2e-234", "NULL", "null", null, "423"};
    try (ColumnVector doubleStringCV = ColumnVector.fromStrings(doubleStrings);
         ColumnVector isDouble = doubleStringCV.isFloat();
         ColumnVector doubles = doubleStringCV.asDoubles();
         ColumnVector expectedDoubles = ColumnVector.fromBoxedDoubles(0d, 0d,
             Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0d, 0d, -0d, 0d, Double.MAX_VALUE,
             Double.POSITIVE_INFINITY, -Double.MAX_VALUE, Double.NEGATIVE_INFINITY, 1.2e-234d, 0d,
             0d, null, 423d);
         ColumnVector expected = ColumnVector.fromBoxedBooleans(false, false, true, true, false,
             false, true, true, true, true, true, true, true, false, false, null, true)) {
      assertColumnsAreEqual(expected, isDouble);
      assertColumnsAreEqual(expectedDoubles, doubles);
    }
  }

  @Test
  void testCreateDurationDays() {
    Integer[] days = {100, 10, 23, 1, -1, 0, Integer.MAX_VALUE, null, Integer.MIN_VALUE};

    try (ColumnVector durationDays = ColumnVector.durationDaysFromBoxedInts(days);
         HostColumnVector hc = durationDays.copyToHost()) {
      assertTrue(hc.hasNulls());
      assertEquals(DType.DURATION_DAYS, hc.getType());
      for (int i = 0; i < days.length; i++) {
        assertEquals(days[i] == null, hc.isNull(i));
        if (!hc.isNull(i)) {
          assertEquals(days[i], hc.getInt(i));
        }
      }
    }
  }

  @Test
  void testCreateDurationSeconds() {
    Long[] secs = {10230L, 10L, 203L, 1L, -1L, 0L, Long.MAX_VALUE, null, Long.MIN_VALUE};

    try (ColumnVector durationSeconds = ColumnVector.durationSecondsFromBoxedLongs(secs);
         HostColumnVector hc = durationSeconds.copyToHost()) {
      assertTrue(hc.hasNulls());
      assertEquals(DType.DURATION_SECONDS, hc.getType());
      for (int i = 0 ; i < secs.length ; i++) {
        assertEquals(secs[i] == null, hc.isNull(i));
        if (!hc.isNull(i)) {
          assertEquals(secs[i], hc.getLong(i));
        }
      }
    }
  }

  @Test
  void testCreateDurationMilliseconds() {
    Long[] ms = {12342340230L, 12112340L, 2230233L, 1L, -1L, 0L, Long.MAX_VALUE, null,
        Long.MIN_VALUE};

    try (ColumnVector durationMs = ColumnVector.durationMilliSecondsFromBoxedLongs(ms);
         HostColumnVector hc = durationMs.copyToHost()) {
      assertTrue(hc.hasNulls());
      assertEquals(DType.DURATION_MILLISECONDS, hc.getType());
      for (int i = 0 ; i < ms.length ; i++) {
        assertEquals(ms[i] == null, hc.isNull(i));
        if (!hc.isNull(i)) {
          assertEquals(ms[i], hc.getLong(i));
        }
      }
    }
  }

  @Test
  void testCreateDurationMicroseconds() {
    Long[] us = {1234234230L, 132350L, 289877803L, 1L, -1L, 0L, Long.MAX_VALUE, null,
        Long.MIN_VALUE};

    try (ColumnVector durationUs = ColumnVector.durationMicroSecondsFromBoxedLongs(us);
         HostColumnVector hc = durationUs.copyToHost()) {
      assertTrue(hc.hasNulls());
      assertEquals(DType.DURATION_MICROSECONDS, hc.getType());
      for (int i = 0 ; i < us.length ; i++) {
        assertEquals(us[i] == null, hc.isNull(i));
        if (!hc.isNull(i)) {
          assertEquals(us[i], hc.getLong(i));
        }
      }
    }
  }

  @Test
  void testCreateDurationNanoseconds() {
    Long[] ns = {1234234230L, 198832350L, 289877803L, 1L, -1L, 0L, Long.MAX_VALUE, null,
        Long.MIN_VALUE};

    try (ColumnVector durationNs = ColumnVector.durationNanoSecondsFromBoxedLongs(ns);
         HostColumnVector hc = durationNs.copyToHost()) {
      assertTrue(hc.hasNulls());
      assertEquals(DType.DURATION_NANOSECONDS, hc.getType());
      for (int i = 0 ; i < ns.length ; i++) {
        assertEquals(ns[i] == null, hc.isNull(i));
        if (!hc.isNull(i)) {
          assertEquals(ns[i], hc.getLong(i));
        }
      }
    }
  }

  @Test
  void testListCv() {
    List<Integer> list1 = Arrays.asList(0, 1, 2, 3);
    List<Integer> list2 = Arrays.asList(6, 2, 4, 5);
    List<Integer> list3 = Arrays.asList(0, 7, 3, 4, 2);

    try(ColumnVector res = ColumnVector.fromLists(new HostColumnVector.ListType(true,
        new HostColumnVector.BasicType(true, DType.INT32)), list1, list2, list3);
        HostColumnVector hcv = res.copyToHost()) {
      List<Integer> ret1 = hcv.getList(0);
      List<Integer> ret2 = hcv.getList(1);
      List<Integer> ret3 = hcv.getList(2);
      assertEquals(list1, ret1, "Lists don't match");
      assertEquals(list2, ret2, "Lists don't match");
      assertEquals(list3, ret3, "Lists don't match");
    }
  }

  @Test
  void testListCvEmpty() {
    List<Integer> list1 = Arrays.asList(0, 1, 2, 3);
    List<Integer> list2 = Arrays.asList(6, 2, 4, 5);
    List<Integer> list3 = new ArrayList<>();

    try(ColumnVector res = ColumnVector.fromLists(new HostColumnVector.ListType(true,
        new HostColumnVector.BasicType(true, DType.INT32)), list1, list2, list3);
        HostColumnVector hcv = res.copyToHost()) {
      List<Integer> ret1 = hcv.getList(0);
      List<Integer> ret2 = hcv.getList(1);
      List<Integer> ret3 = hcv.getList(2);
      assertEquals(list1, ret1, "Lists don't match");
      assertEquals(list2, ret2, "Lists don't match");
      assertEquals(list3, ret3, "Lists don't match");
    }
  }

  @Test
  void testListCvStrings() {
    List<String> list1 = Arrays.asList("0", "1", "2", "3");
    List<String> list2 = Arrays.asList("4", null, "6", null);
    List<String> list3 = null;

    try(ColumnVector res = ColumnVector.fromLists(new HostColumnVector.ListType(true,
        new HostColumnVector.BasicType(true, DType.STRING)), list1, list2, list3);
        HostColumnVector hcv = res.copyToHost()) {
      List<String> ret1 = hcv.getList(0);
      List<String> ret2 = hcv.getList(1);
      List<String> ret3 = hcv.getList(2);
      assertEquals(list1, ret1, "Lists don't match");
      assertEquals(list2, ret2, "Lists don't match");
      assertEquals(list3, ret3, "Lists don't match");
    }
  }

  @Test
  void testListCvDoubles() {
    List<Double> list1 = Arrays.asList(0.1, 1.2, 2.3, 3.4);
    List<Double> list2 = Arrays.asList(6.7, 7.8, 8.9, 5.6);
    List<Double> list3 = Arrays.asList(0.1, 7.8, 3.4, 4.5, 2.3);

    try(ColumnVector res = ColumnVector.fromLists(new HostColumnVector.ListType(true,
        new HostColumnVector.BasicType(true, DType.FLOAT64)), list1, list2, list3);
        HostColumnVector hcv = res.copyToHost()) {
      List<Double> ret1 = hcv.getList(0);
      List<Double> ret2 = hcv.getList(1);
      assertEquals(list1, ret1, "Lists don't match");
      assertEquals(list2, ret2, "Lists don't match");
    }
  }

  @Test
  void testListCvBytes() {
    List<Byte> list1 = Arrays.asList((byte)1, (byte)3, (byte)5, (byte)7);
    List<Byte> list2 = Arrays.asList((byte)0, (byte)2, (byte)4, (byte)6);
    List<Byte> list3 = Arrays.asList((byte)1, (byte)4, (byte)9, (byte)0);

    try(ColumnVector res = ColumnVector.fromLists(new HostColumnVector.ListType(true,
        new HostColumnVector.BasicType(true, DType.INT8)), list1, list2, list3);
        HostColumnVector hcv = res.copyToHost()) {
      List<Byte> ret1 = hcv.getList(0);
      List<Byte> ret2 = hcv.getList(1);
      assertEquals(list1, ret1, "Lists don't match");
      assertEquals(list2, ret2, "Lists don't match");
    }
  }

  @Test
  void testListCvShorts() {
    List<Short> list1 = Arrays.asList((short)1, (short)3, (short)5, (short)7);
    List<Short> list2 = Arrays.asList((short)0, (short)2, (short)4, (short)6);
    List<Short> list3 = Arrays.asList((short)1, (short)4, (short)9, (short)0);

    try(ColumnVector res = ColumnVector.fromLists(new HostColumnVector.ListType(true,
        new HostColumnVector.BasicType(true, DType.INT16)), list1, list2, list3);
        HostColumnVector hcv = res.copyToHost()) {
      List<Short> ret1 = hcv.getList(0);
      List<Short> ret2 = hcv.getList(1);
      assertEquals(list1, ret1, "Lists don't match");
      assertEquals(list2, ret2, "Lists don't match");
    }
  }

  @Test
  void testListCvFloats() {
    List<Float> list1 = Arrays.asList(0.1F, 1.2F, 2.3F, 3.4F);
    List<Float> list2 = Arrays.asList(6.7F, 7.8F, 8.9F, 5.6F);
    List<Float> list3 = Arrays.asList(0.1F, 7.8F, 3.4F, 4.5F, 2.3F);

    try(ColumnVector res = ColumnVector.fromLists(new HostColumnVector.ListType(true,
        new HostColumnVector.BasicType(true, DType.FLOAT32)), list1, list2, list3);
        HostColumnVector hcv = res.copyToHost()) {
      List<Double> ret1 = hcv.getList(0);
      List<Double> ret2 = hcv.getList(1);
      assertEquals(list1, ret1, "Lists don't match");
      assertEquals(list2, ret2, "Lists don't match");
    }
  }

  @Test
  void testListCvLongs() {
    List<Long> list1 = Arrays.asList(10L, 20L, 30L, 40L);
    List<Long> list2 = Arrays.asList(6L, 7L, 8L, 9L);
    List<Long> list3 = Arrays.asList(1L, 100L, 200L, 300L, 400L);

    try(ColumnVector res = ColumnVector.fromLists(new HostColumnVector.ListType(true,
        new HostColumnVector.BasicType(true, DType.INT64)), list1, list2, list3);
        HostColumnVector hcv = res.copyToHost()) {
      List<Long> ret1 = hcv.getList(0);
      List<Long> ret2 = hcv.getList(1);
      assertEquals(list1, ret1, "Lists don't match");
      assertEquals(list2, ret2, "Lists don't match");
    }
  }

  @Test
  void testListCvBools() {
    List<Boolean> list1 = Arrays.asList(true, false, false, true);
    List<Boolean> list2 = Arrays.asList(false, true, false, false);
    List<Boolean> list3 = Arrays.asList(true, true, true, true);

    try(ColumnVector res = ColumnVector.fromLists(new HostColumnVector.ListType(true,
        new HostColumnVector.BasicType(true, DType.BOOL8)), list1, list2, list3);
        HostColumnVector hcv = res.copyToHost()) {
      List<Boolean> ret1 = hcv.getList(0);
      List<Boolean> ret2 = hcv.getList(1);
      assertEquals(list1, ret1, "Lists don't match");
      assertEquals(list2, ret2, "Lists don't match");
    }
  }

  @Test
  void testListOfListsCv() {
    List<Integer> list1 = Arrays.asList(1, 2, 3);
    List<Integer> list2 = Arrays.asList(4, 5, 6);
    List<Integer> list3 = Arrays.asList(10, 20, 30);
    List<Integer> list4 = Arrays.asList(40, 50, 60);
    List<List<Integer>> mainList1 = new ArrayList<>();
    mainList1.add(list1);
    mainList1.add(list2);
    List<List<Integer>> mainList2 = new ArrayList<>();
    mainList2.add(list3);
    mainList2.add(list4);

    try(ColumnVector res = ColumnVector.fromLists(new HostColumnVector.ListType(true,
            new HostColumnVector.ListType(true, new HostColumnVector.BasicType(true, DType.INT32))),
        mainList1, mainList2);
    HostColumnVector hcv = res.copyToHost()) {
      List<List<Integer>> ret1 = hcv.getList(0);
      List<List<Integer>> ret2 = hcv.getList(1);
      assertEquals(mainList1, ret1, "Lists don't match");
      assertEquals(mainList2, ret2, "Lists don't match");
    }
  }

  @Test
  void testListOfListsCvStrings() {
    List<String> list1 = Arrays.asList("1", "23", "10");
    List<String> list2 = Arrays.asList("13", "14", "17");
    List<String> list3 = Arrays.asList("24", "25", "27");
    List<String> list4 = Arrays.asList("29", "88", "19");
    List<List<String>> mainList1 = new ArrayList<>();
    mainList1.add(list1);
    mainList1.add(list2);
    List<List<String>> mainList2 = new ArrayList<>();
    mainList2.add(list3);
    mainList2.add(list4);

    try(ColumnVector res = ColumnVector.fromLists(new HostColumnVector.ListType(true,
        new HostColumnVector.ListType(true,
            new HostColumnVector.BasicType(true, DType.STRING))), mainList1, mainList2);
        HostColumnVector hcv = res.copyToHost()) {
      List<List<String>> ret1 = hcv.getList(0);
      List<List<String>> ret2 = hcv.getList(1);
      assertEquals(mainList1, ret1, "Lists don't match");
      assertEquals(mainList2, ret2, "Lists don't match");
    }
  }

  @Test
  void testListOfListsCvDoubles() {
    List<Double> list1 = Arrays.asList(1.1, 2.2, 3.3);
    List<Double> list2 = Arrays.asList(4.4, 5.5, 6.6);
    List<Double> list3 = Arrays.asList(10.1, 20.2, 30.3);
    List<List<Double>> mainList1 = new ArrayList<>();
    mainList1.add(list1);
    mainList1.add(list2);
    List<List<Double>> mainList2 = new ArrayList<>();
    mainList2.add(list3);

    try(ColumnVector res = ColumnVector.fromLists(new HostColumnVector.ListType(true,
        new HostColumnVector.ListType(true, new HostColumnVector.BasicType(true, DType.FLOAT64))), mainList1, mainList2);
        HostColumnVector hcv = res.copyToHost()) {
      List<List<Double>> ret1 = hcv.getList(0);
      List<List<Double>> ret2 = hcv.getList(1);
      assertEquals(mainList1, ret1, "Lists don't match");
      assertEquals(mainList2, ret2, "Lists don't match");
    }
  }

  @Test
  void testListOfListsCvDecimals() {
    List<BigDecimal> list1 = Arrays.asList(BigDecimal.valueOf(1.1), BigDecimal.valueOf(2.2), BigDecimal.valueOf(3.3));
    List<BigDecimal> list2 = Arrays.asList(BigDecimal.valueOf(4.4), BigDecimal.valueOf(5.5), BigDecimal.valueOf(6.6));
    List<BigDecimal> list3 = Arrays.asList(BigDecimal.valueOf(10.1), BigDecimal.valueOf(20.2), BigDecimal.valueOf(30.3));
    List<List<BigDecimal>> mainList1 = new ArrayList<>();
    mainList1.add(list1);
    mainList1.add(list2);
    List<List<BigDecimal>> mainList2 = new ArrayList<>();
    mainList2.add(list3);

    HostColumnVector.BasicType basicType = new HostColumnVector.BasicType(true, DType.create(DType.DTypeEnum.DECIMAL32, -1));
    try(ColumnVector res = ColumnVector.fromLists(new HostColumnVector.ListType(true,
        new HostColumnVector.ListType(true, basicType)), mainList1, mainList2);
        HostColumnVector hcv = res.copyToHost()) {
      List<List<BigDecimal>> ret1 = hcv.getList(0);
      List<List<BigDecimal>> ret2 = hcv.getList(1);
      assertEquals(mainList1, ret1, "Lists don't match");
      assertEquals(mainList2, ret2, "Lists don't match");
    }
  }

  @Test
  void testConcatLists() {
    List<Integer> list1 = Arrays.asList(0, 1, 2, 3);
    List<Integer> list2 = Arrays.asList(6, 2, 4, 5);
    List<Integer> list3 = Arrays.asList(0, 7, 3, 4, 2);
    List<Integer> list4 = Arrays.asList(10, 11, 12, 13);
    List<Integer> list5 = Arrays.asList(16, 12, 14, 15);
    List<Integer> list6 = Arrays.asList(1, 10, 20, 30, 40);

    try(ColumnVector res1 = ColumnVector.fromLists(new HostColumnVector.ListType(true,
        new HostColumnVector.BasicType(true, DType.INT32)), list1, list2, list3);
    ColumnVector res2 = ColumnVector.fromLists(new HostColumnVector.ListType(true,
        new HostColumnVector.BasicType(true, DType.INT32)), list4, list5, list6);
    ColumnVector v = ColumnVector.concatenate(res1, res2);
    ColumnVector expected =  ColumnVector.fromLists(new HostColumnVector.ListType(true,
        new HostColumnVector.BasicType(true, DType.INT32)), list1, list2, list3, list4, list5, list6)) {
      assertEquals(expected.getRowCount(), 6L, "Expected column row count is incorrect");
      assertColumnsAreEqual(expected, v);
    }
  }


  @Test
  void testConcatListsStrings() {
    List<String> list = Arrays.asList("0", "1", "2", "3");
    List<String> list2 = Arrays.asList("4", null, "6", null);
    List<String> list3 = null;
    try (ColumnVector res1 = ColumnVector.fromLists(new HostColumnVector.ListType(true,
        new HostColumnVector.BasicType(true, DType.STRING)), list, list3);
         ColumnVector res2 = ColumnVector.fromLists(new HostColumnVector.ListType(true,
             new HostColumnVector.BasicType(true, DType.STRING)), list2);
         ColumnVector v = ColumnVector.concatenate(res1, res2);
         ColumnVector expected = ColumnVector.fromLists(new HostColumnVector.ListType(true,
             new HostColumnVector.BasicType(true, DType.STRING)) , list, list3, list2)) {
      assert res1.getNullCount() == 1: "Null count is incorrect on input column";
      assert res2.getNullCount() == 0 : "Null count is incorrect on input column";
      try(ColumnView cView1 = res1.getChildColumnView(0);
          ColumnView cView2 = res2.getChildColumnView(0)) {
        assert cView1.getNullCount() == 0 : "Null count is incorrect on input column";
        assert cView2.getNullCount() == 2 : "Null count is incorrect on input column";
      }
      assertColumnsAreEqual(expected, v);
    }
  }

  @Test
  void testNullsInLists() {
    List<String> val1 = Arrays.asList("Hello", "there");
    List<String> val2 = Arrays.asList("these");
    List<String> val3 = null;
    List<String> val4 = Arrays.asList();
    List<String> val5 = Arrays.asList("ARe", "some");
    List<String> val6 = Arrays.asList("test", "strings");
    try(ColumnVector expected = ColumnVector.fromLists(
        new HostColumnVector.ListType(true,
            new HostColumnVector.BasicType(true, DType.STRING)),
        val1, val2, val3, val4, val5, val6);
        HostColumnVector hostColumnVector = expected.copyToHost()) {
      List<String> ret1 = hostColumnVector.getList(0);
      List<String> ret2 = hostColumnVector.getList(1);
      List<String> ret3 = hostColumnVector.getList(2);
      List<String> ret4 = hostColumnVector.getList(3);
      List<String> ret5 = hostColumnVector.getList(4);
      List<String> ret6 = hostColumnVector.getList(5);
      assertEquals(val1, ret1, "Lists don't match");
      assertEquals(val2, ret2, "Lists don't match");
      assertEquals(val3, ret3, "Lists don't match");
      //TODO this is not clear semantically to me right now
      assertEquals(val4, ret4, "Lists should be empty");
      assertEquals(val5, ret5, "Lists don't match");
      assertEquals(val6, ret6, "Lists don't match");
    }
  }

  @Test
  void testHcvOfInts() {
    List<Integer> val1 = Arrays.asList(1, 22);
    List<Integer> val2 = Arrays.asList(333);
    List<Integer> val3 = null;
    List<Integer> val4 = Arrays.asList();
    List<Integer> val5 = Arrays.asList(4444, 55555);
    List<Integer> val6 = Arrays.asList(666666, 7777777);
    try(ColumnVector expected = ColumnVector.fromLists(
        new HostColumnVector.ListType(true,
            new HostColumnVector.BasicType(true, DType.INT32)),
        val1, val2, val3, val4, val5, val6);
        HostColumnVector hostColumnVector = expected.copyToHost()) {
      List<String> ret1 = hostColumnVector.getList(0);
      List<String> ret2 = hostColumnVector.getList(1);
      List<String> ret3 = hostColumnVector.getList(2);
      List<String> ret4 = hostColumnVector.getList(3);
      List<String> ret5 = hostColumnVector.getList(4);
      List<String> ret6 = hostColumnVector.getList(5);
      assertEquals(val1, ret1, "Lists don't match");
      assertEquals(val2, ret2, "Lists don't match");
      assertEquals(val3, ret3, "Lists don't match");
      assertEquals(val4, ret4, "Lists don't match");
      assertEquals(val5, ret5, "Lists don't match");
      assertEquals(val6, ret6, "Lists don't match");
    }
  }

  @Test
  void testHcvOfDecimals() {
    List<BigDecimal>[] data = new List[6];
    data[0] = Arrays.asList(BigDecimal.ONE, BigDecimal.TEN);
    data[1] = Arrays.asList(BigDecimal.ZERO);
    data[2] = null;
    data[3] = Arrays.asList();
    data[4] = Arrays.asList(BigDecimal.valueOf(123), BigDecimal.valueOf(1, -2));
    data[5] = Arrays.asList(BigDecimal.valueOf(100, -3), BigDecimal.valueOf(2, -4));
    try(ColumnVector expected = ColumnVector.fromLists(
        new HostColumnVector.ListType(true,
            new HostColumnVector.BasicType(true, DType.create(DType.DTypeEnum.DECIMAL32, 0))), data);
        HostColumnVector hcv = expected.copyToHost()) {
      for (int i = 0; i < data.length; i++) {
        if (data[i] == null) {
          assertNull(hcv.getList(i));
          continue;
        }
        List<BigDecimal> exp = data[i].stream()
            .map((dec -> (dec == null) ? null : dec.setScale(0, RoundingMode.UNNECESSARY)))
            .collect(Collectors.toList());
        assertEquals(exp, hcv.getList(i));
      }
    }
  }

  @Test
  void testConcatListsOfLists() {
    List<Integer> list1 = Arrays.asList(1, 2, 3);
    List<Integer> list2 = Arrays.asList(4, 5, 6);
    List<Integer> list3 = Arrays.asList(10, 20, 30);
    List<Integer> list4 = Arrays.asList(40, 50, 60);
    List<List<Integer>> mainList = new ArrayList<>();
    mainList.add(list1);
    mainList.add(list2);
    List<List<Integer>> mainList2 = new ArrayList<>();
    mainList2.add(list3);
    mainList2.add(list4);
    try (ColumnVector res1 =  ColumnVector.fromLists(new HostColumnVector.ListType(true,
        new HostColumnVector.ListType(true, new HostColumnVector.BasicType(true, DType.INT32))), mainList);
         ColumnVector res2 = ColumnVector.fromLists(new HostColumnVector.ListType(true,
             new HostColumnVector.ListType(true, new HostColumnVector.BasicType(true, DType.INT32))), mainList2);
         ColumnVector v = ColumnVector.concatenate(res1, res2);
         ColumnVector expected = ColumnVector.fromLists(new HostColumnVector.ListType(true,
             new HostColumnVector.ListType(true, new HostColumnVector.BasicType(true, DType.INT32))), mainList, mainList2)) {
      assertColumnsAreEqual(expected, v);
    }
  }

  @Test
  void testContiguousSplitConstructor() {
    try (Table tmp = new Table.TestBuilder().column(1, 2).column(3, 4).build();
         ContiguousTable ct = tmp.contiguousSplit()[0]) {
      // table should not be referencing the device buffer yet
      assertEquals(1, ct.getBuffer().getRefCount());

      // get the table to force it to be instantiated
      Table ignored = ct.getTable();

      // one reference for the device buffer itself, two more for the column using it
      assertEquals(3, ct.getBuffer().getRefCount());
    }
  }

  @Test
  void testHcvForStruct() {
    List<HostColumnVector.DataType> children =
        Arrays.asList(new HostColumnVector.BasicType(true, DType.INT32),
            new HostColumnVector.BasicType(true, DType.INT64));
    HostColumnVector.StructType type = new HostColumnVector.StructType(true, children);
    List data1 = Arrays.asList(10, 20L);
    List data2 = Arrays.asList(50, 60L);
    List data3 = Arrays.asList(null, 80L);
    List data4 = null;
    HostColumnVector.StructData structData1 = new HostColumnVector.StructData(data1);
    HostColumnVector.StructData structData2 = new HostColumnVector.StructData(data2);
    HostColumnVector.StructData structData3 = new HostColumnVector.StructData(data3);
    HostColumnVector.StructData structData4 = new HostColumnVector.StructData(data4);
    try (HostColumnVector hcv = HostColumnVector.fromStructs(type, Arrays.asList(structData1, structData2, structData3, structData4));
         ColumnVector columnVector = hcv.copyToDevice();
         HostColumnVector hcv1 = columnVector.copyToHost();
         ColumnVector expected = hcv1.copyToDevice()) {
      assertEquals(expected.getRowCount(), 4L, "Expected column row count is incorrect");
      HostColumnVector.StructData retData1 = hcv1.getStruct(0);
      HostColumnVector.StructData retData2 = hcv1.getStruct(1);
      HostColumnVector.StructData retData3 = hcv1.getStruct(2);
      HostColumnVector.StructData retData4 = hcv1.getStruct(3);
      assertEquals(data1, retData1.dataRecord);
      assertEquals(data2, retData2.dataRecord);
      assertEquals(data3, retData3.dataRecord);
      assertEquals(data4, retData4);
      assertStructColumnsAreEqual(expected, columnVector);
    }
  }

  @Test
  void testStructChildValidity() {
    List<HostColumnVector.DataType> children =
        Arrays.asList(new HostColumnVector.BasicType(true, DType.INT32),
            new HostColumnVector.BasicType(true, DType.INT64));
    HostColumnVector.StructType type = new HostColumnVector.StructType(true, children);
    List data1 = Arrays.asList(1, 2L);
    List data2 = Arrays.asList(4, 5L);
    List data3 = null;
    List data4 = Arrays.asList(8, null);
    HostColumnVector.StructData structData1 = new HostColumnVector.StructData(data1);
    HostColumnVector.StructData structData2 = new HostColumnVector.StructData(data2);
    HostColumnVector.StructData structData3 = new HostColumnVector.StructData(data3);
    HostColumnVector.StructData structData4 = new HostColumnVector.StructData(data4);
    try (HostColumnVector hcv = HostColumnVector.fromStructs(type, Arrays.asList(structData1, structData2, structData3, structData4));
         ColumnVector columnVector = hcv.copyToDevice();
         HostColumnVector hcv1 = columnVector.copyToHost();
         ColumnVector expected = hcv1.copyToDevice()) {
      assertFalse(hcv.isNull(0));
      assertFalse(hcv.isNull(1));
      assertTrue(hcv.isNull(2));
      assertFalse(hcv.isNull(3));
      HostColumnVectorCore intChildCol = hcv.children.get(0);
      HostColumnVectorCore longChildCol = hcv.children.get(1);
      assertFalse(intChildCol.isNull(0));
      assertFalse(intChildCol.isNull(1));
      assertTrue(intChildCol.isNull(2));
      assertFalse(intChildCol.isNull(3));
      assertFalse(longChildCol.isNull(0));
      assertFalse(longChildCol.isNull(1));
      assertTrue(longChildCol.isNull(2));
      assertTrue(longChildCol.isNull(3));

      intChildCol = hcv1.children.get(0);
      longChildCol = hcv1.children.get(1);

      assertFalse(intChildCol.isNull(0));
      assertFalse(intChildCol.isNull(1));
      assertTrue(intChildCol.isNull(2));
      assertFalse(intChildCol.isNull(3));
      assertFalse(longChildCol.isNull(0));
      assertFalse(longChildCol.isNull(1));
      assertTrue(longChildCol.isNull(2));
      assertTrue(longChildCol.isNull(3));
      assertStructColumnsAreEqual(expected, columnVector);
    }
  }

  @Test
  void testGetMapValue() {
    List<HostColumnVector.StructData> list1 = Arrays.asList(new HostColumnVector.StructData(Arrays.asList("a", "b")));
    List<HostColumnVector.StructData> list2 = Arrays.asList(new HostColumnVector.StructData(Arrays.asList("a", "c")));
    List<HostColumnVector.StructData> list3 = Arrays.asList(new HostColumnVector.StructData(Arrays.asList("e", "d")));
    HostColumnVector.StructType structType = new HostColumnVector.StructType(true, Arrays.asList(new HostColumnVector.BasicType(true, DType.STRING),
        new HostColumnVector.BasicType(true, DType.STRING)));
    try (ColumnVector cv = ColumnVector.fromLists(new HostColumnVector.ListType(true, structType), list1, list2, list3);
         ColumnVector res = cv.getMapValue(Scalar.fromString("a"));
         ColumnVector expected = ColumnVector.fromStrings("b", "c", null)) {
      assertColumnsAreEqual(expected, res);
    }
  }

  @Test
  void testGetMapKeyExistence() {
    List<HostColumnVector.StructData> list1 = Arrays.asList(new HostColumnVector.StructData("a", "b"));
    List<HostColumnVector.StructData> list2 = Arrays.asList(new HostColumnVector.StructData("a", "c"));
    List<HostColumnVector.StructData> list3 = Arrays.asList(new HostColumnVector.StructData("e", "d"));
    List<HostColumnVector.StructData> list4 = Arrays.asList(new HostColumnVector.StructData("a", "g"));
    List<HostColumnVector.StructData> list5 = Arrays.asList(new HostColumnVector.StructData("a", null));
    List<HostColumnVector.StructData> list6 = Arrays.asList(new HostColumnVector.StructData(null, null));
    List<HostColumnVector.StructData> list7 = Arrays.asList(new HostColumnVector.StructData());
    HostColumnVector.StructType structType = new HostColumnVector.StructType(true, Arrays.asList(new HostColumnVector.BasicType(true, DType.STRING),
            new HostColumnVector.BasicType(true, DType.STRING)));
    try (ColumnVector cv = ColumnVector.fromLists(new HostColumnVector.ListType(true, structType), list1, list2, list3, list4, list5, list6, list7);
         ColumnVector resValidKey = cv.getMapKeyExistence(Scalar.fromString("a"));
         ColumnVector expectedValid = ColumnVector.fromBoxedBooleans(true, true, false, true, true, false, false);
         ColumnVector expectedNull = ColumnVector.fromBoxedBooleans(false, false, false, false, false, false, false);
         ColumnVector resNullKey = cv.getMapKeyExistence(Scalar.fromNull(DType.STRING))) {
      assertColumnsAreEqual(expectedValid, resValidKey);
      assertColumnsAreEqual(expectedNull, resNullKey);
    }

    AssertionError e = assertThrows(AssertionError.class, () -> {
      try (ColumnVector cv = ColumnVector.fromLists(new HostColumnVector.ListType(true, structType), list1, list2, list3, list4, list5, list6, list7);
           ColumnVector resNullKey = cv.getMapKeyExistence(null)) {
      }
    });
    assertTrue(e.getMessage().contains("target string may not be null"));
  }


  @Test
  void testListOfStructsOfStructs() {
    List<HostColumnVector.StructData> list1 = Arrays.asList(
        new HostColumnVector.StructData(Arrays.asList(new HostColumnVector.StructData(Arrays.asList("a")))));
    List<HostColumnVector.StructData> list2 = Arrays.asList(
        new HostColumnVector.StructData(Arrays.asList(new HostColumnVector.StructData(Arrays.asList("b")))));
    List<HostColumnVector.StructData> list3 = Arrays.asList(
        new HostColumnVector.StructData(Arrays.asList(new HostColumnVector.StructData(Arrays.asList("c")))));
    HostColumnVector.StructType structType = new HostColumnVector.StructType(true, Arrays.asList(new HostColumnVector.StructType(true,
        Arrays.asList(new HostColumnVector.BasicType(true, DType.STRING)))));
    HostColumnVector.ListType schema = new HostColumnVector.ListType(true, structType);
    try (ColumnVector cv = ColumnVector.fromLists(schema, list1, list2, list3);
         HostColumnVector hostColumnVector = cv.copyToHost();
         ColumnVector expected = hostColumnVector.copyToDevice()) {
      assertColumnsAreEqual(expected, cv);
    }
  }

  @Test
  void testCopyToColumnVector() {
    List<Integer> list1 = Arrays.asList(10, 11, 12, 13);
    List<Integer> list2 = Arrays.asList(16, 12, 14, 15);
    List<Integer> list3 = Arrays.asList(0, 7, 3, 4, 2);

    try(ColumnVector res = ColumnVector.fromLists(new HostColumnVector.ListType(true,
        new HostColumnVector.BasicType(true, DType.INT32)), list1, list2, list3);
        ColumnView childColumnView = res.getChildColumnView(0);
        ColumnVector copiedChildCv = childColumnView.copyToColumnVector();
        ColumnVector expected =
            ColumnVector.fromInts(10, 11, 12, 13, 16, 12, 14, 15, 0, 7, 3, 4, 2)) {
      assertColumnsAreEqual(expected, copiedChildCv);
    }
  }

  @Test
  void testGetJSONObject() {
    String jsonString = "{ \"store\": {\n" +
        "    \"book\": [\n" +
        "      { \"category\": \"reference\",\n" +
        "        \"author\": \"Nigel Rees\",\n" +
        "        \"title\": \"Sayings of the Century\",\n" +
        "        \"price\": 8.95\n" +
        "      },\n" +
        "      { \"category\": \"fiction\",\n" +
        "        \"author\": \"Evelyn Waugh\",\n" +
        "        \"title\": \"Sword of Honour\",\n" +
        "        \"price\": 12.99\n" +
        "      },\n" +
        "      { \"category\": \"fiction\",\n" +
        "        \"author\": \"Herman Melville\",\n" +
        "        \"title\": \"Moby Dick\",\n" +
        "        \"isbn\": \"0-553-21311-3\",\n" +
        "        \"price\": 8.99\n" +
        "      },\n" +
        "      { \"category\": \"fiction\",\n" +
        "        \"author\": \"J. R. R. Tolkien\",\n" +
        "        \"title\": \"The Lord of the Rings\",\n" +
        "        \"isbn\": \"0-395-19395-8\",\n" +
        "        \"price\": 22.99\n" +
        "      }\n" +
        "    ],\n" +
        "    \"bicycle\": {\n" +
        "      \"color\": \"red\",\n" +
        "      \"price\": 19.95\n" +
        "    }\n" +
        "  }\n" +
        "}";

    try (ColumnVector json = ColumnVector.fromStrings(jsonString, jsonString);
         ColumnVector expectedAuthors = ColumnVector.fromStrings("[\"Nigel Rees\",\"Evelyn " +
             "Waugh\",\"Herman Melville\",\"J. R. R. Tolkien\"]", "[\"Nigel Rees\",\"Evelyn " +
             "Waugh\",\"Herman Melville\",\"J. R. R. Tolkien\"]");
         Scalar path = Scalar.fromString("$.store.book[*].author");
         ColumnVector gotAuthors = json.getJSONObject(path)) {
      assertColumnsAreEqual(expectedAuthors, gotAuthors);
    }
  }

  @Test
  void testMakeStructEmpty() {
    final int numRows = 10;
    try (ColumnVector expected = ColumnVector.emptyStructs(new StructType(false, new ArrayList<>()), numRows);
         ColumnVector created = ColumnVector.makeStruct(numRows)) {
      assertColumnsAreEqual(expected, created);
    }
  }

  @Test
  void testMakeStruct() {
    try (ColumnVector expected = ColumnVector.fromStructs(new StructType(false,
            Arrays.asList(
                new BasicType(false, DType.INT32),
                new BasicType(false, DType.INT32),
                new BasicType(false, DType.INT32))),
        new HostColumnVector.StructData(1, 2, 3),
        new HostColumnVector.StructData(4, 5, 6));
         ColumnVector child1 = ColumnVector.fromInts(1, 4);
         ColumnVector child2 = ColumnVector.fromInts(2, 5);
         ColumnVector child3 = ColumnVector.fromInts(3, 6);
         ColumnVector created = ColumnVector.makeStruct(child1, child2, child3)) {
      assertColumnsAreEqual(expected, created);
    }
  }

  @Test
  void testMakeListEmpty() {
    final int numRows = 4;
    List<List<String>> emptyListOfList = new ArrayList<>();
    emptyListOfList.add(Arrays.asList());
    try (
        ColumnVector expectedList =
             ColumnVector.fromLists(
                 new ListType(false, new BasicType(false, DType.STRING)),
                 Arrays.asList(),
                 Arrays.asList(),
                 Arrays.asList(),
                 Arrays.asList());
         ColumnVector expectedListOfList = ColumnVector.fromLists(new HostColumnVector.ListType(false,
                 new HostColumnVector.ListType(false,
                     new HostColumnVector.BasicType(false, DType.STRING))),
             emptyListOfList, emptyListOfList, emptyListOfList, emptyListOfList);

         ColumnVector createdList = ColumnVector.makeList(numRows, DType.STRING);
         ColumnVector createdListOfList = ColumnVector.makeList(createdList)) {
      assertColumnsAreEqual(expectedList, createdList);
      assertColumnsAreEqual(expectedListOfList, createdListOfList);
    }
  }

  @Test
  void testMakeList() {
    List<Integer> list1 = Arrays.asList(1, 3);
    List<Integer> list2 = Arrays.asList(2, 4);
    List<Integer> list3 = Arrays.asList(5, 7, 9);
    List<Integer> list4 = Arrays.asList(6, 8, 10);
    List<List<Integer>> mainList1 = new ArrayList<>(Arrays.asList(list1, list3));
    List<List<Integer>> mainList2 = new ArrayList<>(Arrays.asList(list2, list4));
    try (ColumnVector expectedList1 =
             ColumnVector.fromLists(new ListType(false,
                 new BasicType(false, DType.INT32)), list1, list2);
         ColumnVector expectedList2 =
             ColumnVector.fromLists(new ListType(false,
                 new BasicType(false, DType.INT32)), list3, list4);
         ColumnVector expectedListOfList = ColumnVector.fromLists(new HostColumnVector.ListType(true,
                 new HostColumnVector.ListType(true, new HostColumnVector.BasicType(true, DType.INT32))),
             mainList1, mainList2);
         ColumnVector child1 = ColumnVector.fromInts(1, 2);
         ColumnVector child2 = ColumnVector.fromInts(3, 4);
         ColumnVector child3 = ColumnVector.fromInts(5, 6);
         ColumnVector child4 = ColumnVector.fromInts(7, 8);
         ColumnVector child5 = ColumnVector.fromInts(9, 10);
         ColumnVector createdList1 = ColumnVector.makeList(child1, child2);
         ColumnVector createdList2 = ColumnVector.makeList(child3, child4, child5);
         ColumnVector createdListOfList = ColumnVector.makeList(createdList1, createdList2);
         HostColumnVector hcv = createdListOfList.copyToHost()) {

      assertColumnsAreEqual(expectedList1, createdList1);
      assertColumnsAreEqual(expectedList2, createdList2);
      assertColumnsAreEqual(expectedListOfList, createdListOfList);

      List<List<Integer>> ret1 = hcv.getList(0);
      List<List<Integer>> ret2 = hcv.getList(1);
      assertEquals(mainList1, ret1, "Lists don't match");
      assertEquals(mainList2, ret2, "Lists don't match");
    }
  }

  @Test
  void testReplaceLeafNodeInList() {
    try (
        ColumnVector c1 = ColumnVector.fromInts(1, 2);
        ColumnVector c2 = ColumnVector.fromInts(8, 3);
        ColumnVector c3 = ColumnVector.fromInts(9, 8);
        ColumnVector c4 = ColumnVector.fromInts(2, 6);
        ColumnVector expected = ColumnVector.makeList(c1, c2, c3, c4);
        ColumnVector child1 =
            ColumnVector.decimalFromDoubles(DType.create(DType.DTypeEnum.DECIMAL64, 3),
                RoundingMode.HALF_UP, 770.892, 961.110);
        ColumnVector child2 =
            ColumnVector.decimalFromDoubles(DType.create(DType.DTypeEnum.DECIMAL64, 3),
                RoundingMode.HALF_UP, 524.982, 479.946);
        ColumnVector child3 =
            ColumnVector.decimalFromDoubles(DType.create(DType.DTypeEnum.DECIMAL64, 3),
                RoundingMode.HALF_UP, 346.997, 479.946);
        ColumnVector child4 =
            ColumnVector.decimalFromDoubles(DType.create(DType.DTypeEnum.DECIMAL64, 3),
                RoundingMode.HALF_UP, 87.764, 414.239);
        ColumnVector created = ColumnVector.makeList(child1, child2, child3, child4);
        ColumnVector newChild = ColumnVector.fromInts(1, 8, 9, 2, 2, 3, 8, 6);
        ColumnView replacedView = created.replaceListChild(newChild)) {
      try (ColumnVector replaced = replacedView.copyToColumnVector()) {
        assertColumnsAreEqual(expected, replaced);
      }
    }
  }

  @Test
  void testReplaceLeafNodeInListWithIllegal() {
    Exception e = assertThrows(IllegalArgumentException.class, () -> {
      try (ColumnVector child1 =
               ColumnVector.decimalFromDoubles(DType.create(DType.DTypeEnum.DECIMAL64, 3),
                   RoundingMode.HALF_UP, 770.892, 961.110);
           ColumnVector child2 =
               ColumnVector.decimalFromDoubles(DType.create(DType.DTypeEnum.DECIMAL64, 3),
                   RoundingMode.HALF_UP, 524.982, 479.946);
           ColumnVector child3 =
               ColumnVector.decimalFromDoubles(DType.create(DType.DTypeEnum.DECIMAL64, 3),
                   RoundingMode.HALF_UP, 346.997, 479.946);
           ColumnVector child4 =
               ColumnVector.decimalFromDoubles(DType.create(DType.DTypeEnum.DECIMAL64, 3),
                   RoundingMode.HALF_UP, 87.764, 414.239);
           ColumnVector created = ColumnVector.makeList(child1, child2, child3, child4);
           ColumnVector newChild = ColumnVector.fromInts(0, 1, 8, 9, 2, 2, 3, 8, 6);
           ColumnView replacedView = created.replaceListChild(newChild)) {
      }
    });
    assertTrue(e.getMessage().contains("Child row count doesn't match the old child"));
  }

  @Test
  void testReplaceColumnInStruct() {
    try (ColumnVector expected = ColumnVector.fromStructs(new StructType(false,
            Arrays.asList(
                new BasicType(false, DType.INT32),
                new BasicType(false, DType.INT32),
                new BasicType(false, DType.INT32))),
        new HostColumnVector.StructData(1, 5, 3),
        new HostColumnVector.StructData(4, 9, 6));
         ColumnVector child1 = ColumnVector.fromInts(1, 4);
         ColumnVector child2 = ColumnVector.fromInts(2, 5);
         ColumnVector child3 = ColumnVector.fromInts(3, 6);
         ColumnVector created = ColumnVector.makeStruct(child1, child2, child3);
         ColumnVector replaceWith = ColumnVector.fromInts(5, 9);
         ColumnView replacedView = created.replaceChildrenWithViews(new int[]{1},
             new ColumnVector[]{replaceWith})) {
      try (ColumnVector replaced = replacedView.copyToColumnVector()) {
        assertColumnsAreEqual(expected, replaced);
      }
    }
  }

  @Test
  void testReplaceIllegalIndexColumnInStruct() {
    Exception e = assertThrows(IllegalArgumentException.class, () -> {
      try (ColumnVector child1 = ColumnVector.fromInts(1, 4);
           ColumnVector child2 = ColumnVector.fromInts(2, 5);
           ColumnVector child3 = ColumnVector.fromInts(3, 6);
           ColumnVector created = ColumnVector.makeStruct(child1, child2, child3);
           ColumnVector replaceWith = ColumnVector.fromInts(5, 9);
           ColumnView replacedView = created.replaceChildrenWithViews(new int[]{5},
               new ColumnVector[]{replaceWith})) {
      }
    });
    assertTrue(e.getMessage().contains("One or more invalid child indices passed to be replaced"));
  }

  @Test
  void testReplaceSameIndexColumnInStruct() {
    Exception e = assertThrows(IllegalArgumentException.class, () -> {
      try (ColumnVector child1 = ColumnVector.fromInts(1, 4);
           ColumnVector child2 = ColumnVector.fromInts(2, 5);
           ColumnVector child3 = ColumnVector.fromInts(3, 6);
           ColumnVector created = ColumnVector.makeStruct(child1, child2, child3);
           ColumnVector replaceWith = ColumnVector.fromInts(5, 9);
           ColumnView replacedView = created.replaceChildrenWithViews(new int[]{1, 1},
               new ColumnVector[]{replaceWith, replaceWith})) {
      }
    });
    assertTrue(e.getMessage().contains("Duplicate mapping found for replacing child index"));
  }
}
