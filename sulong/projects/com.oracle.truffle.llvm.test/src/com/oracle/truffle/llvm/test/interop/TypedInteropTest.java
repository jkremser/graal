/*
 * Copyright (c) 2018, 2019, Oracle and/or its affiliates.
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are
 * permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this list of
 * conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of
 * conditions and the following disclaimer in the documentation and/or other materials provided
 * with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors may be used to
 * endorse or promote products derived from this software without specific prior written
 * permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS
 * OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 * COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE
 * GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED
 * AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.oracle.truffle.llvm.test.interop;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.llvm.test.interop.values.ArrayObject;
import com.oracle.truffle.llvm.test.interop.values.NullValue;
import com.oracle.truffle.llvm.test.interop.values.StructObject;
import com.oracle.truffle.llvm.test.interop.values.TestCallback;
import com.oracle.truffle.tck.TruffleRunner;
import com.oracle.truffle.tck.TruffleRunner.Inject;
import java.util.HashMap;
import java.util.Map;
import org.graalvm.polyglot.Value;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import org.junit.BeforeClass;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(TruffleRunner.class)
public class TypedInteropTest extends InteropTestBase {

    private static TruffleObject testLibrary;

    @BeforeClass
    public static void loadTestBitcode() {
        testLibrary = InteropTestBase.loadTestBitcodeInternal("typedInterop");
    }

    private static StructObject makePoint(int x, int y) {
        Map<String, Object> point = new HashMap<>();
        point.put("x", x);
        point.put("y", y);
        point.put("length", new TestCallback(0, (args) -> {
            int px = (int) point.get("x");
            int py = (int) point.get("y");
            return Math.sqrt(px * (double) px + py * (double) py);
        }));
        point.put("add", new TestCallback(1, (args) -> {
            int px = (int) point.get("x");
            int py = (int) point.get("y");
            Value other = runWithPolyglot.getPolyglotContext().asValue(args[0]);
            return makePoint(px + other.getMember("x").asInt(), py + other.getMember("y").asInt());
        }));
        return new StructObject(point);
    }

    private static void checkPoint(StructObject p, int x, int y) {
        Assert.assertEquals("x", x, p.get("x"));
        Assert.assertEquals("y", y, p.get("y"));
    }

    public static class DistSquaredNode extends SulongTestNode {

        public DistSquaredNode() {
            super(testLibrary, "distSquared");
        }
    }

    @Test
    public void testDistSquared(@Inject(DistSquaredNode.class) CallTarget distSquared) {
        Object ret = distSquared.call(makePoint(3, 7), makePoint(6, 3));
        Assert.assertEquals(25, ret);
    }

    public static class FlipPointNode extends SulongTestNode {

        public FlipPointNode() {
            super(testLibrary, "flipPoint");
        }
    }

    @Test
    public void testFlipPoint(@Inject(FlipPointNode.class) CallTarget flipPoint) {
        StructObject point = makePoint(42, 24);
        flipPoint.call(point);
        Assert.assertEquals("x", 24, point.get("x"));
        Assert.assertEquals("y", 42, point.get("y"));
    }

    public static class SumPointsNode extends SulongTestNode {

        public SumPointsNode() {
            super(testLibrary, "sumPoints");
        }
    }

    @Test
    public void testSumPoints(@Inject(SumPointsNode.class) CallTarget sumPoints) {
        ArrayObject array = new ArrayObject(makePoint(13, 7), makePoint(3, 6), makePoint(8, 5));
        Object ret = sumPoints.call(array);
        Assert.assertEquals(42, ret);
    }

    public static class FillPointsNode extends SulongTestNode {

        public FillPointsNode() {
            super(testLibrary, "fillPoints");
        }
    }

    @Test
    public void testFillPoints(@Inject(FillPointsNode.class) CallTarget fillPoints) {
        StructObject[] arr = new StructObject[42];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = makePoint(0, 0);
        }

        fillPoints.call(new ArrayObject((Object[]) arr), 3, 1);

        for (int i = 0; i < arr.length; i++) {
            Assert.assertEquals("x", 3, arr[i].get("x"));
            Assert.assertEquals("y", 1, arr[i].get("y"));
        }
    }

    public static class ModifyAndCallNode extends SulongTestNode {

        public ModifyAndCallNode() {
            super(testLibrary, "modifyAndCall");
        }
    }

    @Test
    public void testModifyAndCall(@Inject(ModifyAndCallNode.class) CallTarget modifyAndCall) {
        StructObject point = makePoint(3, 4);
        Object length = modifyAndCall.call(point);

        checkPoint(point, 6, 8);
        Assert.assertEquals("length", 10.0, length);
    }

    public static class AddAndSwapPoint extends SulongTestNode {

        public AddAndSwapPoint() {
            super(testLibrary, "addAndSwapPoint");
        }
    }

    @Test
    public void testAddAndSwapPoint(@Inject(AddAndSwapPoint.class) CallTarget addAndSwapPoint) {
        StructObject point = makePoint(39, 17);
        Object ret = addAndSwapPoint.call(point, 3, 7);

        Assert.assertThat("ret", ret, is(instanceOf(StructObject.class)));
        checkPoint((StructObject) ret, 24, 42);
    }

    public static class FillNestedNode extends SulongTestNode {

        public FillNestedNode() {
            super(testLibrary, "fillNested");
        }
    }

    private static Object createNested() {
        Object ret = new NullValue();
        for (int i = 0; i < 10; i++) {
            Map<String, Object> nested = new HashMap<>();
            nested.put("arr", new ArrayObject(makePoint(0, 0), makePoint(0, 0), makePoint(0, 0), makePoint(0, 0), makePoint(0, 0)));
            nested.put("direct", makePoint(0, 0));
            nested.put("next", ret);
            ret = new StructObject(nested);
        }
        return ret;
    }

    private static void checkNested(Object ret) {
        int value = 42;
        Object obj = ret;
        while (obj instanceof StructObject) {
            StructObject nested = (StructObject) obj;
            ArrayObject arr = (ArrayObject) nested.get("arr");
            for (int i = 0; i < 5; i++) {
                StructObject p = (StructObject) arr.get(i);
                Assert.assertEquals("x", value++, p.get("x"));
                Assert.assertEquals("y", value++, p.get("y"));
            }

            StructObject p = (StructObject) nested.get("direct");
            Assert.assertEquals("x", value++, p.get("x"));
            Assert.assertEquals("y", value++, p.get("y"));

            obj = nested.get("next");
        }
    }

    @Test
    public void testFillNested(@Inject(FillNestedNode.class) CallTarget fillNested) {
        Object nested = createNested();
        fillNested.call(nested);
        checkNested(nested);
    }

    public static class AccessBitFieldsNode extends SulongTestNode {

        public AccessBitFieldsNode() {
            super(testLibrary, "accessBitFields");
        }
    }

    private static Object createBitFieldsStruct() {
        Map<String, Object> members = new HashMap<>();

        members.put("x", 1);
        members.put("y", 2);
        members.put("z", 3);

        return new StructObject(members);
    }

    @Test
    @Ignore("GR-17155")
    public void testAccessBitFields(@Inject(AccessBitFieldsNode.class) CallTarget accessBitFields) {
        int res = (int) accessBitFields.call(createBitFieldsStruct());
        Assert.assertTrue(res == 6);
    }

    public static class FillFusedArrayNode extends SulongTestNode {

        public FillFusedArrayNode() {
            super(testLibrary, "fillFusedArray");
        }
    }

    private static Object createFusedArray() {
        Map<String, Object> ret = new HashMap<>();
        ret.put("origin", makePoint(0, 0));
        ArrayObject array = new ArrayObject(makePoint(0, 0), makePoint(0, 0), makePoint(0, 0), makePoint(0, 0), makePoint(0, 0), makePoint(0, 0), makePoint(0, 0));
        ret.put("path", array);

        return new StructObject(ret);
    }

    private static void checkFusedArray(Object res) {
        Assert.assertTrue(res instanceof StructObject);
        if (res instanceof StructObject) {
            StructObject struct = (StructObject) res;
            checkPoint((StructObject) struct.get("origin"), 3, 7);
            ArrayObject array = (ArrayObject) struct.get("path");
            for (int i = 0; i < 7; i++) {
                StructObject point = (StructObject) array.get(i);
                checkPoint(point, 2 * i, 5 * i);
            }
        }
    }

    @Test
    public void testFillFusedArray(@Inject(FillFusedArrayNode.class) CallTarget fillFusedArray) {
        Object fusedArray = createFusedArray();
        fillFusedArray.call(fusedArray);
        checkFusedArray(fusedArray);
    }

    private static StructObject makeComplex(double re, double im) {
        Map<String, Object> ret = new HashMap<>();
        ret.put("re", re);
        ret.put("im", im);
        return new StructObject(ret);
    }

    public static class ReadTypeMismatchNode extends SulongTestNode {

        public ReadTypeMismatchNode() {
            super(testLibrary, "readTypeMismatch");
        }
    }

    @Test
    public void testReadTypeMismatch(@Inject(ReadTypeMismatchNode.class) CallTarget readTypeMismatch) {
        double expected = 42.0;
        Object ret = readTypeMismatch.call(makeComplex(expected, 0.0));
        Assert.assertEquals(Double.doubleToLongBits(expected), ret);
    }

    public static class WriteTypeMismatchNode extends SulongTestNode {

        public WriteTypeMismatchNode() {
            super(testLibrary, "writeTypeMismatch");
        }
    }

    @Test
    public void writeTypeMismatch(@Inject(WriteTypeMismatchNode.class) CallTarget writeTypeMismatch) {
        double expected = 37.0;
        StructObject arg = makeComplex(0.0, 0.0);
        writeTypeMismatch.call(arg, Double.doubleToLongBits(expected));
        Assert.assertEquals(expected, arg.get("re"));
    }
}
