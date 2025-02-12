/*
 * Copyright (c) 2016, 2018, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
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
package org.graalvm.compiler.serviceprovider;

import static java.lang.Thread.currentThread;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.graalvm.compiler.serviceprovider.SpeculationReasonGroup.SpeculationContextObject;

import jdk.vm.ci.code.BytecodePosition;
import jdk.vm.ci.code.VirtualObject;
import jdk.vm.ci.meta.ResolvedJavaField;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.ResolvedJavaType;
import jdk.vm.ci.meta.SpeculationLog.SpeculationReason;
import jdk.vm.ci.meta.SpeculationLog.SpeculationReasonEncoding;
import jdk.vm.ci.services.JVMCIPermission;
import jdk.vm.ci.services.Services;

/**
 * JDK 8 version of {@link GraalServices}.
 */
public final class GraalServices {

    private GraalServices() {
    }

    /**
     * Gets an {@link Iterable} of the providers available for a given service.
     *
     * @throws SecurityException if on JDK8 and a security manager is present and it denies
     *             {@link JVMCIPermission}
     */
    public static <S> Iterable<S> load(Class<S> service) {
        assert !service.getName().startsWith("jdk.vm.ci") : "JVMCI services must be loaded via " + Services.class.getName();
        return Services.load(service);
    }

    /**
     * Gets the provider for a given service for which at most one provider must be available.
     *
     * @param service the service whose provider is being requested
     * @param required specifies if an {@link InternalError} should be thrown if no provider of
     *            {@code service} is available
     * @return the requested provider if available else {@code null}
     * @throws SecurityException if on JDK8 and a security manager is present and it denies
     *             {@link JVMCIPermission}
     */
    public static <S> S loadSingle(Class<S> service, boolean required) {
        assert !service.getName().startsWith("jdk.vm.ci") : "JVMCI services must be loaded via " + Services.class.getName();
        Iterable<S> providers = load(service);
        S singleProvider = null;
        try {
            for (Iterator<S> it = providers.iterator(); it.hasNext();) {
                singleProvider = it.next();
                if (it.hasNext()) {
                    S other = it.next();
                    throw new InternalError(String.format("Multiple %s providers found: %s, %s", service.getName(), singleProvider.getClass().getName(), other.getClass().getName()));
                }
            }
        } catch (ServiceConfigurationError e) {
            // If the service is required we will bail out below.
        }
        if (singleProvider == null) {
            if (required) {
                throw new InternalError(String.format("No provider for %s found", service.getName()));
            }
        }
        return singleProvider;
    }

    /**
     * Gets the class file bytes for {@code c}.
     */
    @SuppressWarnings("unused")
    public static InputStream getClassfileAsStream(Class<?> c) throws IOException {
        String classfilePath = c.getName().replace('.', '/') + ".class";
        ClassLoader cl = c.getClassLoader();
        if (cl == null) {
            return ClassLoader.getSystemResourceAsStream(classfilePath);
        }
        return cl.getResourceAsStream(classfilePath);
    }

    private static final ClassLoader JVMCI_LOADER = GraalServices.class.getClassLoader();
    private static final ClassLoader JVMCI_PARENT_LOADER = JVMCI_LOADER == null ? null : JVMCI_LOADER.getParent();
    static {
        assert JVMCI_PARENT_LOADER == null || JVMCI_PARENT_LOADER.getParent() == null;
    }

    /**
     * Determines if invoking {@link Object#toString()} on an instance of {@code c} will only run
     * trusted code.
     */
    public static boolean isToStringTrusted(Class<?> c) {
        ClassLoader cl = c.getClassLoader();
        return cl == null || cl == JVMCI_LOADER || cl == JVMCI_PARENT_LOADER;
    }

    /**
     * An implementation of {@link SpeculationReason} based on direct, unencoded values.
     */
    static final class DirectSpeculationReason implements SpeculationReason {
        final int groupId;
        final String groupName;
        final Object[] context;
        private SpeculationReasonEncoding encoding;

        DirectSpeculationReason(int groupId, String groupName, Object[] context) {
            this.groupId = groupId;
            this.groupName = groupName;
            this.context = context;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof DirectSpeculationReason) {
                DirectSpeculationReason that = (DirectSpeculationReason) obj;
                return this.groupId == that.groupId && Arrays.equals(this.context, that.context);
            }
            return false;
        }

        @Override
        public int hashCode() {
            return groupId + Arrays.hashCode(this.context);
        }

        @Override
        public String toString() {
            return String.format("%s@%d%s", groupName, groupId, Arrays.toString(context));
        }

        @Override
        public SpeculationReasonEncoding encode(Supplier<SpeculationReasonEncoding> encodingSupplier) {
            if (encoding == null) {
                encoding = encodingSupplier.get();
                encoding.addInt(groupId);
                for (Object o : context) {
                    if (o == null) {
                        encoding.addInt(0);
                    } else {
                        addNonNullObject(encoding, o);
                    }
                }
            }
            return encoding;
        }

        static void addNonNullObject(SpeculationReasonEncoding encoding, Object o) {
            Class<? extends Object> c = o.getClass();
            if (c == String.class) {
                encoding.addString((String) o);
            } else if (c == Byte.class) {
                encoding.addByte((Byte) o);
            } else if (c == Short.class) {
                encoding.addShort((Short) o);
            } else if (c == Character.class) {
                encoding.addShort((Character) o);
            } else if (c == Integer.class) {
                encoding.addInt((Integer) o);
            } else if (c == Long.class) {
                encoding.addLong((Long) o);
            } else if (c == Float.class) {
                encoding.addInt(Float.floatToRawIntBits((Float) o));
            } else if (c == Double.class) {
                encoding.addLong(Double.doubleToRawLongBits((Double) o));
            } else if (o instanceof Enum) {
                encoding.addInt(((Enum<?>) o).ordinal());
            } else if (o instanceof ResolvedJavaMethod) {
                encoding.addMethod((ResolvedJavaMethod) o);
            } else if (o instanceof ResolvedJavaType) {
                encoding.addType((ResolvedJavaType) o);
            } else if (o instanceof ResolvedJavaField) {
                encoding.addField((ResolvedJavaField) o);
            } else if (o instanceof SpeculationContextObject) {
                SpeculationContextObject sco = (SpeculationContextObject) o;
                // These are compiler objects which all have the same class
                // loader so the class name uniquely identifies the class.
                encoding.addString(o.getClass().getName());
                sco.accept(new EncodingAdapter(encoding));
            } else if (o.getClass() == BytecodePosition.class) {
                BytecodePosition p = (BytecodePosition) o;
                while (p != null) {
                    encoding.addInt(p.getBCI());
                    encoding.addMethod(p.getMethod());
                    p = p.getCaller();
                }
            } else {
                throw new IllegalArgumentException("Unsupported type for encoding: " + c.getName());
            }
        }
    }

    static class EncodingAdapter implements SpeculationContextObject.Visitor {
        private final SpeculationReasonEncoding encoding;

        EncodingAdapter(SpeculationReasonEncoding encoding) {
            this.encoding = encoding;
        }

        @Override
        public void visitBoolean(boolean v) {
            encoding.addByte(v ? 1 : 0);
        }

        @Override
        public void visitByte(byte v) {
            encoding.addByte(v);
        }

        @Override
        public void visitChar(char v) {
            encoding.addShort(v);
        }

        @Override
        public void visitShort(short v) {
            encoding.addInt(v);
        }

        @Override
        public void visitInt(int v) {
            encoding.addInt(v);
        }

        @Override
        public void visitLong(long v) {
            encoding.addLong(v);
        }

        @Override
        public void visitFloat(float v) {
            encoding.addInt(Float.floatToRawIntBits(v));
        }

        @Override
        public void visitDouble(double v) {
            encoding.addLong(Double.doubleToRawLongBits(v));
        }

        @Override
        public void visitObject(Object v) {
            if (v == null) {
                encoding.addInt(0);
            } else {
                DirectSpeculationReason.addNonNullObject(encoding, v);
            }
        }
    }

    static SpeculationReason createSpeculationReason(int groupId, String groupName, Object... context) {
        return new DirectSpeculationReason(groupId, groupName, context);
    }

    /**
     * Gets a unique identifier for this execution such as a process ID or a
     * {@linkplain #getGlobalTimeStamp() fixed timestamp}.
     */
    public static String getExecutionID() {
        try {
            String runtimeName = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
            try {
                int index = runtimeName.indexOf('@');
                if (index != -1) {
                    long pid = Long.parseLong(runtimeName.substring(0, index));
                    return Long.toString(pid);
                }
            } catch (NumberFormatException e) {
            }
            return runtimeName;
        } catch (LinkageError err) {
            return String.valueOf(getGlobalTimeStamp());
        }
    }

    private static final AtomicLong globalTimeStamp = new AtomicLong();

    /**
     * Gets a time stamp for the current process. This method will always return the same value for
     * the current VM execution.
     */
    public static long getGlobalTimeStamp() {
        if (globalTimeStamp.get() == 0) {
            globalTimeStamp.compareAndSet(0, System.currentTimeMillis());
        }
        return globalTimeStamp.get();
    }

    /**
     * Lazy initialization of Java Management Extensions (JMX).
     */
    static class Lazy {
        static final com.sun.management.ThreadMXBean threadMXBean = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
    }

    /**
     * Returns an approximation of the total amount of memory, in bytes, allocated in heap memory
     * for the thread of the specified ID. The returned value is an approximation because some Java
     * virtual machine implementations may use object allocation mechanisms that result in a delay
     * between the time an object is allocated and the time its size is recorded.
     * <p>
     * If the thread of the specified ID is not alive or does not exist, this method returns
     * {@code -1}. If thread memory allocation measurement is disabled, this method returns
     * {@code -1}. A thread is alive if it has been started and has not yet died.
     * <p>
     * If thread memory allocation measurement is enabled after the thread has started, the Java
     * virtual machine implementation may choose any time up to and including the time that the
     * capability is enabled as the point where thread memory allocation measurement starts.
     *
     * @param id the thread ID of a thread
     * @return an approximation of the total memory allocated, in bytes, in heap memory for a thread
     *         of the specified ID if the thread of the specified ID exists, the thread is alive,
     *         and thread memory allocation measurement is enabled; {@code -1} otherwise.
     *
     * @throws IllegalArgumentException if {@code id} {@code <=} {@code 0}.
     * @throws UnsupportedOperationException if the Java virtual machine implementation does not
     *             {@linkplain #isThreadAllocatedMemorySupported() support} thread memory allocation
     *             measurement.
     */
    public static long getThreadAllocatedBytes(long id) {
        return Lazy.threadMXBean.getThreadAllocatedBytes(id);
    }

    /**
     * Convenience method for calling {@link #getThreadAllocatedBytes(long)} with the id of the
     * current thread.
     */
    public static long getCurrentThreadAllocatedBytes() {
        return Lazy.threadMXBean.getThreadAllocatedBytes(currentThread().getId());
    }

    /**
     * Returns the total CPU time for the current thread in nanoseconds. The returned value is of
     * nanoseconds precision but not necessarily nanoseconds accuracy. If the implementation
     * distinguishes between user mode time and system mode time, the returned CPU time is the
     * amount of time that the current thread has executed in user mode or system mode.
     *
     * @return the total CPU time for the current thread if CPU time measurement is enabled;
     *         {@code -1} otherwise.
     *
     * @throws UnsupportedOperationException if the Java virtual machine does not
     *             {@linkplain #isCurrentThreadCpuTimeSupported() support} CPU time measurement for
     *             the current thread
     */
    public static long getCurrentThreadCpuTime() {
        return Lazy.threadMXBean.getCurrentThreadCpuTime();
    }

    /**
     * Determines if the Java virtual machine implementation supports thread memory allocation
     * measurement.
     */
    public static boolean isThreadAllocatedMemorySupported() {
        return Lazy.threadMXBean.isThreadAllocatedMemorySupported();
    }

    /**
     * Determines if the Java virtual machine supports CPU time measurement for the current thread.
     */
    public static boolean isCurrentThreadCpuTimeSupported() {
        return Lazy.threadMXBean.isCurrentThreadCpuTimeSupported();
    }

    /**
     * Gets the input arguments passed to the Java virtual machine which does not include the
     * arguments to the {@code main} method. This method returns an empty list if there is no input
     * argument to the Java virtual machine.
     * <p>
     * Some Java virtual machine implementations may take input arguments from multiple different
     * sources: for examples, arguments passed from the application that launches the Java virtual
     * machine such as the 'java' command, environment variables, configuration files, etc.
     * <p>
     * Typically, not all command-line options to the 'java' command are passed to the Java virtual
     * machine. Thus, the returned input arguments may not include all command-line options.
     *
     * @return the input arguments to the JVM or {@code null} if they are unavailable
     */
    public static List<String> getInputArguments() {
        return java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments();
    }

    /**
     * Returns the fused multiply add of the three arguments; that is, returns the exact product of
     * the first two arguments summed with the third argument and then rounded once to the nearest
     * {@code float}.
     */
    public static float fma(float a, float b, float c) {
        // Copy from JDK 9
        float result = (float) (((double) a * (double) b) + c);
        return result;
    }

    /**
     * Returns the fused multiply add of the three arguments; that is, returns the exact product of
     * the first two arguments summed with the third argument and then rounded once to the nearest
     * {@code double}.
     */
    public static double fma(double a, double b, double c) {
        // Copy from JDK 9
        if (Double.isNaN(a) || Double.isNaN(b) || Double.isNaN(c)) {
            return Double.NaN;
        } else { // All inputs non-NaN
            boolean infiniteA = Double.isInfinite(a);
            boolean infiniteB = Double.isInfinite(b);
            boolean infiniteC = Double.isInfinite(c);
            double result;

            if (infiniteA || infiniteB || infiniteC) {
                if (infiniteA && b == 0.0 || infiniteB && a == 0.0) {
                    return Double.NaN;
                }
                double product = a * b;
                if (Double.isInfinite(product) && !infiniteA && !infiniteB) {
                    assert Double.isInfinite(c);
                    return c;
                } else {
                    result = product + c;
                    assert !Double.isFinite(result);
                    return result;
                }
            } else { // All inputs finite
                BigDecimal product = (new BigDecimal(a)).multiply(new BigDecimal(b));
                if (c == 0.0) {
                    if (a == 0.0 || b == 0.0) {
                        return a * b + c;
                    } else {
                        return product.doubleValue();
                    }
                } else {
                    return product.add(new BigDecimal(c)).doubleValue();
                }
            }
        }
    }

    @SuppressWarnings("unused")
    public static VirtualObject createVirtualObject(ResolvedJavaType type, int id, boolean isAutoBox) {
        return VirtualObject.get(type, id, isAutoBox);
    }
}
