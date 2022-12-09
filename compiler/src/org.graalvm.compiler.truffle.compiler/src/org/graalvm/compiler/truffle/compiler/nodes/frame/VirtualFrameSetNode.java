/*
 * Copyright (c) 2016, 2022, Oracle and/or its affiliates. All rights reserved.
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
package org.graalvm.compiler.truffle.compiler.nodes.frame;

import static org.graalvm.compiler.nodeinfo.NodeCycles.CYCLES_0;
import static org.graalvm.compiler.nodeinfo.NodeSize.SIZE_0;

import org.graalvm.compiler.core.common.type.PrimitiveStamp;
import org.graalvm.compiler.core.common.type.Stamp;
import org.graalvm.compiler.core.common.type.StampFactory;
import org.graalvm.compiler.graph.NodeClass;
import org.graalvm.compiler.nodeinfo.NodeInfo;
import org.graalvm.compiler.nodes.ConstantNode;
import org.graalvm.compiler.nodes.NodeView;
import org.graalvm.compiler.nodes.ValueNode;
import org.graalvm.compiler.nodes.calc.ReinterpretNode;
import org.graalvm.compiler.nodes.calc.ZeroExtendNode;
import org.graalvm.compiler.nodes.graphbuilderconf.InvocationPlugin.Receiver;
import org.graalvm.compiler.nodes.spi.Virtualizable;
import org.graalvm.compiler.nodes.spi.VirtualizerTool;
import org.graalvm.compiler.nodes.virtual.VirtualObjectNode;

import jdk.vm.ci.meta.JavaKind;

@NodeInfo(cycles = CYCLES_0, size = SIZE_0)
public final class VirtualFrameSetNode extends VirtualFrameAccessorNode implements Virtualizable {
    public static final NodeClass<VirtualFrameSetNode> TYPE = NodeClass.create(VirtualFrameSetNode.class);

    @Input private ValueNode value;

    private final byte accessFlags;

    public VirtualFrameSetNode(Receiver frame, int frameSlotIndex, int accessTag, ValueNode value, VirtualFrameAccessType type, byte accessFlags) {
        super(TYPE, StampFactory.forVoid(), frame, frameSlotIndex, accessTag, type);
        this.value = value;
        this.accessFlags = accessFlags;
    }

    public VirtualFrameSetNode(NewFrameNode frame, int frameSlotIndex, int accessTag, ValueNode value, VirtualFrameAccessType type, byte accessFlags) {
        super(TYPE, StampFactory.forVoid(), frame, frameSlotIndex, accessTag, type);
        this.value = value;
        this.accessFlags = accessFlags;
    }

    public VirtualFrameSetNode(Receiver frame, int frameSlotIndex, int accessTag, ValueNode value, VirtualFrameAccessType type) {
        this(frame, frameSlotIndex, accessTag, value, type, VirtualFrameAccessFlags.NON_STATIC);
    }

    @Override
    public void virtualize(VirtualizerTool tool) {
        JavaKind valueKind = value.getStackKind();
        if (type == VirtualFrameAccessType.Auxiliary) {
            ValueNode dataAlias = tool.getAlias(frame.getObjectArray(type));

            assert valueKind == JavaKind.Object;
            // no tags array
            if (dataAlias instanceof VirtualObjectNode) {
                VirtualObjectNode dataVirtual = (VirtualObjectNode) dataAlias;

                if (frameSlotIndex < dataVirtual.entryCount()) {
                    if (tool.setVirtualEntry(dataVirtual, frameSlotIndex, value, valueKind, -1)) {
                        tool.delete();
                        return;
                    }
                }
            }
        } else {
            ValueNode tagAlias = tool.getAlias(frame.getTagArray(type));
            ValueNode dataAlias = tool.getAlias(valueKind == JavaKind.Object ? frame.getObjectArray(type) : frame.getPrimitiveArray(type));
            if (tagAlias instanceof VirtualObjectNode && dataAlias instanceof VirtualObjectNode) {

                VirtualObjectNode tagVirtual = (VirtualObjectNode) tagAlias;
                VirtualObjectNode dataVirtual = (VirtualObjectNode) dataAlias;

                if (frameSlotIndex < tagVirtual.entryCount() && frameSlotIndex < dataVirtual.entryCount()) {
                    if ((accessFlags & VirtualFrameAccessFlags.SET_TAG_FLAG) != 0) {
                        if ((accessFlags & VirtualFrameAccessFlags.STATIC_FLAG) != 0) {
                            tool.setVirtualEntry(tagVirtual, frameSlotIndex, getConstantWithStaticModifier(accessTag));
                        } else {
                            tool.setVirtualEntry(tagVirtual, frameSlotIndex, getConstant(accessTag));
                        }
                    }
                    ValueNode actualValue = maybeExtendForOSRStaticAccess(tool);
                    if (tool.setVirtualEntry(dataVirtual, frameSlotIndex, actualValue, valueKind == JavaKind.Object ? JavaKind.Object : JavaKind.Long, -1)) {
                        if (valueKind == JavaKind.Object) {
                            // clear out native entry
                            ValueNode primitiveAlias = tool.getAlias(frame.getPrimitiveArray(type));
                            if (primitiveAlias instanceof VirtualObjectNode) {
                                tool.setVirtualEntry((VirtualObjectNode) primitiveAlias, frameSlotIndex, ConstantNode.defaultForKind(JavaKind.Long, graph()), JavaKind.Long, -1);
                            }
                        }
                        tool.delete();
                        return;
                    }
                }
            }
        }

        /*
         * Deoptimization is our only option here. We cannot go back to a UnsafeStoreNode because we
         * do not have a FrameState to use for the memory store.
         */
        insertDeoptimization(tool);
    }

    private ValueNode maybeExtendForOSRStaticAccess(VirtualizerTool tool) {
        if (!isOSRRawStaticAccess()) {
            return value;
        }
        Stamp valueStamp = value.stamp(NodeView.DEFAULT);
        if (!(valueStamp instanceof PrimitiveStamp)) {
            return value;
        }
        // Force all primitive to be long for bytecode OSR frame.
        return extendForOSRStaticAccess(tool, tool.getAlias(value));
    }

    private static ValueNode extendForOSRStaticAccess(VirtualizerTool tool, ValueNode entry) {
        JavaKind entryKind = entry.stamp(NodeView.DEFAULT).getStackKind();
        if (entryKind == JavaKind.Long) {
            return entry;
        }
        assert entryKind.isPrimitive();
        ValueNode tmpValue = entry;
        if (entryKind.isNumericFloat()) {
            // Convert from numeric float to numeric integer
            entryKind = entryKind == JavaKind.Float ? JavaKind.Int : JavaKind.Long;
            tmpValue = new ReinterpretNode(entryKind, tmpValue);
            tool.addNode(tmpValue);
        }
        if (entryKind != JavaKind.Long) {
            tmpValue = new ZeroExtendNode(tmpValue, JavaKind.Long.getBitCount());
            tool.addNode(tmpValue);
        }
        assert tmpValue.stamp(NodeView.DEFAULT).getStackKind() == JavaKind.Long;
        return tmpValue;
    }

    private boolean isOSRRawStaticAccess() {
        return ((accessFlags & VirtualFrameAccessFlags.STATIC_FLAG) != 0) && frame.isBytecodeOSRTransferTarget();
    }
}
