/*
 * Copyright (c) 2016, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.graalvm.compiler.core.test;

import java.lang.reflect.Field;

import org.graalvm.compiler.nodes.StructuredGraph;
import org.graalvm.compiler.nodes.StructuredGraph.AllowAssumptions;
import org.graalvm.compiler.options.OptionValues;
import org.graalvm.compiler.phases.common.CanonicalizerPhase;
import org.graalvm.compiler.phases.tiers.PhaseContext;
import org.graalvm.compiler.virtual.phases.ea.PartialEscapePhase;
import org.junit.Test;

import jdk.vm.ci.code.InstalledCode;
import jdk.vm.ci.meta.ResolvedJavaMethod;

public class UnsafeVirtualizationTest extends GraalCompilerTest {

    public static class A {
        int f1;
        int f2;
    }

    private static final long AF1Offset;
    private static final long AF2Offset;
    static {
        long o1 = -1;
        long o2 = -1;
        try {
            Field f1 = A.class.getDeclaredField("f1");
            Field f2 = A.class.getDeclaredField("f2");
            o1 = UNSAFE.objectFieldOffset(f1);
            o2 = UNSAFE.objectFieldOffset(f2);
        } catch (NoSuchFieldException | SecurityException e) {
            throw new AssertionError(e);
        }
        AF1Offset = o1;
        AF2Offset = o2;
    }

    public static int unsafeSnippet1(double i1) {
        A a = new A();
        UNSAFE.putDouble(a, AF1Offset, i1);
        return UNSAFE.getInt(a, AF1Offset) + UNSAFE.getInt(a, AF2Offset);
    }

    public static int unsafeSnippet2(int i1) {
        A a = new A();
        UNSAFE.putDouble(a, AF1Offset, i1);
        a.f2 = i1;
        return (int) UNSAFE.getDouble(a, AF1Offset);
    }

    public static int unsafeSnippet3(int i1) {
        A a = new A();
        UNSAFE.putDouble(a, AF1Offset, i1);
        UNSAFE.putInt(a, AF2Offset, i1);
        return (int) UNSAFE.getDouble(a, AF1Offset);
    }

    @Test
    public void testUnsafePEA01() {
        testPartialEscapeReadElimination("unsafeSnippet1", false, 1.0);
        testPartialEscapeReadElimination("unsafeSnippet1", true, 1.0);
    }

    @Test
    public void testUnsafePEA02() {
        testPartialEscapeReadElimination("unsafeSnippet2", false, 1);
        testPartialEscapeReadElimination("unsafeSnippet2", true, 1);
    }

    @Test
    public void testUnsafePEA03() {
        testPartialEscapeReadElimination("unsafeSnippet3", false, 1);
        testPartialEscapeReadElimination("unsafeSnippet3", true, 1);
    }

    public void testPartialEscapeReadElimination(String snippet, boolean canonicalizeBefore, Object... args) {
        ResolvedJavaMethod method = getResolvedJavaMethod(snippet);
        StructuredGraph graph = parseEager(snippet, AllowAssumptions.NO);
        OptionValues options = graph.getOptions();
        PhaseContext context = getDefaultHighTierContext();
        CanonicalizerPhase canonicalizer = new CanonicalizerPhase();
        if (canonicalizeBefore) {
            canonicalizer.apply(graph, context);
        }
        Result r = executeExpected(method, null, args);
        new PartialEscapePhase(true, true, canonicalizer, null, options).apply(graph, context);
        try {
            InstalledCode code = getCode(method, graph);
            Object result = code.executeVarargs(args);
            assertEquals(r, new Result(result, null));
        } catch (Throwable e) {
            assertFalse(true, e.toString());
        }
    }
}
