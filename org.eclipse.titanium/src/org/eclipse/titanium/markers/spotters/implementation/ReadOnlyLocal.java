/******************************************************************************
 * Copyright (c) 2000-2021 Ericsson Telecom AB
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/org/documents/epl-2.0/EPL-2.0.html
 ******************************************************************************/
package org.eclipse.titanium.markers.spotters.implementation;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.titan.designer.AST.IVisitableNode;
import org.eclipse.titan.designer.AST.Value;
import org.eclipse.titan.designer.AST.TTCN3.definitions.Def_Var;
import org.eclipse.titan.designer.AST.TTCN3.definitions.Def_Var_Template;
import org.eclipse.titan.designer.AST.TTCN3.types.ComponentTypeBody;
import org.eclipse.titan.designer.parsers.CompilationTimeStamp;
import org.eclipse.titanium.markers.spotters.BaseModuleCodeSmellSpotter;
import org.eclipse.titanium.markers.types.CodeSmellType;

//FIXME improve to not depend on getWritten, so that that could be removed too
public class ReadOnlyLocal {
	private ReadOnlyLocal() {
		throw new AssertionError("Noninstantiable");
	}

	public static class Var extends BaseModuleCodeSmellSpotter {
		public static final String READONLY = "The {0} seems to be never written, maybe it could be a constant";

		public Var() {
			super(CodeSmellType.READONLY_LOC_VARIABLE);
		}

		@Override
		public void process(final IVisitableNode node, final Problems problems) {
			if (node instanceof Def_Var) {
				final Def_Var s = (Def_Var) node;
				if (!(s.getMyScope() instanceof ComponentTypeBody) && !s.getWritten()) {
					final Value initialValue = s.getInitialValue();
					final CompilationTimeStamp ct = CompilationTimeStamp.getBaseTimestamp();
					if (initialValue != null && !initialValue.getIsErroneous(ct) && !initialValue.isUnfoldable(ct)) {
						final String msg = MessageFormat.format(READONLY, s.getDescription());
						problems.report(s.getIdentifier().getLocation(), msg);
					}
				}
			}
		}

		@Override
		public List<Class<? extends IVisitableNode>> getStartNode() {
			final List<Class<? extends IVisitableNode>> ret = new ArrayList<Class<? extends IVisitableNode>>(1);
			ret.add(Def_Var.class);
			return ret;
		}
	}

	public static class VarTemplate extends BaseModuleCodeSmellSpotter {
		public static final String READONLY = "The {0} seems to be never written, maybe it could be a template";

		public VarTemplate() {
			super(CodeSmellType.READONLY_LOC_VARIABLE);
		}

		@Override
		public void process(final IVisitableNode node, final Problems problems) {
			if (node instanceof Def_Var_Template) {
				final Def_Var_Template s = (Def_Var_Template) node;
				if (!s.getWritten()) {
					final String msg = MessageFormat.format(READONLY, s.getDescription());
					problems.report(s.getIdentifier().getLocation(), msg);
				}
			}
		}

		@Override
		public List<Class<? extends IVisitableNode>> getStartNode() {
			final List<Class<? extends IVisitableNode>> ret = new ArrayList<Class<? extends IVisitableNode>>(1);
			ret.add(Def_Var_Template.class);
			return ret;
		}
	}
}