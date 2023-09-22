/*
 * Copyright (c) 2011, 2022, Oracle and/or its affiliates. All rights reserved.
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
package jdk.graal.compiler.nodes.extended;

import static jdk.graal.compiler.nodeinfo.InputType.Anchor;
import static jdk.graal.compiler.nodeinfo.InputType.Guard;
import static jdk.graal.compiler.nodeinfo.NodeCycles.CYCLES_0;
import static jdk.graal.compiler.nodeinfo.NodeSize.SIZE_0;

import jdk.graal.compiler.core.common.type.StampFactory;
import jdk.graal.compiler.graph.Node;
import jdk.graal.compiler.graph.NodeClass;
import jdk.graal.compiler.graph.spi.NodeWithIdentity;
import jdk.graal.compiler.nodeinfo.NodeInfo;
import jdk.graal.compiler.nodes.FixedWithNextNode;
import jdk.graal.compiler.nodes.debug.ControlFlowAnchored;
import jdk.graal.compiler.nodes.spi.Canonicalizable;
import jdk.graal.compiler.nodes.spi.CanonicalizerTool;
import jdk.graal.compiler.nodes.spi.LIRLowerable;
import jdk.graal.compiler.nodes.spi.NodeLIRBuilderTool;
import jdk.graal.compiler.nodes.spi.Virtualizable;
import jdk.graal.compiler.nodes.spi.VirtualizerTool;

/**
 * This node can be used to keep nodes below a certain point in the graph by using this node as an
 * Anchor or Guard input.
 *
 * This node must not move in the CFG, because that would change the anchor point. So optimizations
 * like de-duplication are not allowed and this node implements {@link NodeWithIdentity}. However,
 * duplication is allowed, i.e., it is on purpose that this node does not implement
 * {@link ControlFlowAnchored}.
 */
@NodeInfo(allowedUsageTypes = {Anchor, Guard}, cycles = CYCLES_0, size = SIZE_0)
public final class ValueAnchorNode extends FixedWithNextNode implements LIRLowerable, Canonicalizable, Virtualizable, AnchoringNode, GuardingNode, NodeWithIdentity {

    public static final NodeClass<ValueAnchorNode> TYPE = NodeClass.create(ValueAnchorNode.class);

    public ValueAnchorNode() {
        super(TYPE, StampFactory.forVoid());
    }

    @Override
    public void generate(NodeLIRBuilderTool gen) {
        // Nothing to emit, since this node is used for structural purposes only.
    }

    @Override
    public Node canonical(CanonicalizerTool tool) {
        if (tool.allUsagesAvailable() && hasNoUsages()) {
            return null;
        }
        return this;
    }

    @Override
    public void virtualize(VirtualizerTool tool) {
        tool.delete();
    }
}
