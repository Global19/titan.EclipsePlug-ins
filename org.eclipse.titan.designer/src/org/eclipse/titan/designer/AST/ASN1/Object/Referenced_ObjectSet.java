/******************************************************************************
 * Copyright (c) 2000-2021 Ericsson Telecom AB
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/org/documents/epl-2.0/EPL-2.0.html
 ******************************************************************************/
package org.eclipse.titan.designer.AST.ASN1.Object;

import java.text.MessageFormat;

import org.eclipse.titan.designer.AST.ASTVisitor;
import org.eclipse.titan.designer.AST.Assignment;
import org.eclipse.titan.designer.AST.IReferenceChain;
import org.eclipse.titan.designer.AST.IReferenceChainElement;
import org.eclipse.titan.designer.AST.ISetting;
import org.eclipse.titan.designer.AST.Identifier;
import org.eclipse.titan.designer.AST.Location;
import org.eclipse.titan.designer.AST.Reference;
import org.eclipse.titan.designer.AST.ReferenceChain;
import org.eclipse.titan.designer.AST.Scope;
import org.eclipse.titan.designer.AST.ASN1.ASN1Object;
import org.eclipse.titan.designer.AST.ASN1.Defined_Reference;
import org.eclipse.titan.designer.AST.ASN1.IObjectSet_Element;
import org.eclipse.titan.designer.AST.ASN1.InformationFromObj;
import org.eclipse.titan.designer.AST.ASN1.ObjectSet;
import org.eclipse.titan.designer.AST.ASN1.ObjectSetElement_Visitor;
import org.eclipse.titan.designer.AST.ASN1.Parameterised_Reference;
import org.eclipse.titan.designer.compiler.JavaGenData;
import org.eclipse.titan.designer.editors.ProposalCollector;
import org.eclipse.titan.designer.editors.actions.DeclarationCollector;
import org.eclipse.titan.designer.parsers.CompilationTimeStamp;

/**
 * Referenced ObjectSet.
 *
 * @author Kristof Szabados
 */
public final class Referenced_ObjectSet extends ObjectSet implements IObjectSet_Element, IReferenceChainElement {

	private static final String OBJECTSETEXPECTED = "This is not an objectsetreference: `{0}''";
	public static final String MISMATCH = "ObjectClass mismatch: ObjectSet of class `{0}'' was expected instead of `{1}''";

	private final Reference reference;

	private ObjectSet osReferenced;
	private ObjectSet_definition referencedLast;

	public Referenced_ObjectSet(final Reference reference) {
		this.reference = reference;

		if (null != reference) {
			reference.setFullNameParent(this);
		}
	}

	@Override
	/** {@inheritDoc} */
	public Referenced_ObjectSet newInstance() {
		return new Referenced_ObjectSet(reference);
	}

	@Override
	/** {@inheritDoc} */
	public IObjectSet_Element newOseInstance() {
		return newInstance();
	}

	@Override
	/** {@inheritDoc} */
	public String chainedDescription() {
		return "objectSet reference: " + reference;
	}

	@Override
	/** {@inheritDoc} */
	public Location getChainLocation() {
		if (null != reference && null != reference.getLocation()) {
			return reference.getLocation();
		}

		return null;
	}

	@Override
	/** {@inheritDoc} */
	public void setMyScope(final Scope scope) {
		super.setMyScope(scope);
		if (null != reference) {
			reference.setMyScope(scope);
		}
	}

	public ObjectSet getRefd(final CompilationTimeStamp timestamp, final IReferenceChain referenceChain) {
		if (referenceChain.add(this)) {
			if (osReferenced != null && lastTimeChecked != null && !lastTimeChecked.isLess(timestamp)) {
				return osReferenced;
			}
			final Assignment assignment = reference.getRefdAssignment(timestamp, true, referenceChain);
			if (null != assignment) {
				final ISetting setting = reference.getRefdSetting(timestamp);
				if (null != setting && !Setting_type.S_ERROR.equals(setting.getSettingtype())) {
					if (Setting_type.S_OS.equals(setting.getSettingtype())) {
						osReferenced = (ObjectSet) setting;
						return osReferenced;
					}

					location.reportSemanticError(MessageFormat.format(OBJECTSETEXPECTED, reference.getDisplayName()));
				}
			}
		}

		osReferenced = new ObjectSet_definition();
		osReferenced.setFullNameParent(this);
		osReferenced.setMyGovernor(getMyGovernor());
		osReferenced.setIsErroneous(true);

		return osReferenced;
	}

	@Override
	/** {@inheritDoc} */
	public ObjectSet_definition getRefdLast(final CompilationTimeStamp timestamp, final IReferenceChain referenceChain) {
		final boolean newChain = null == referenceChain;
		IReferenceChain temporalReferenceChain;
		if (newChain) {
			temporalReferenceChain = ReferenceChain.getInstance(IReferenceChain.CIRCULARREFERENCE, true);
		} else {
			temporalReferenceChain = referenceChain;
		}

		final ObjectSet tempObjectSet = getRefd(timestamp, temporalReferenceChain);
		referencedLast = tempObjectSet.getRefdLast(timestamp, temporalReferenceChain);

		if (newChain) {
			temporalReferenceChain.release();
		}

		return referencedLast;
	}

	public boolean isReferencedInformationFromObj() {
		return reference instanceof InformationFromObj;
	}

	public boolean isReferencedParameterisedReference() {
		return reference instanceof Parameterised_Reference;
	}

	public boolean isReferencedDefinedReference() {
		return reference instanceof Defined_Reference;
	}

	public Identifier getId() {
		return reference.getId();
	}

	@Override
	/** {@inheritDoc} */
	public void check(final CompilationTimeStamp timestamp) {
		if (null != lastTimeChecked && !lastTimeChecked.isLess(timestamp)) {
			return;
		}

		lastTimeChecked = timestamp;

		if (null == myGovernor) {
			return;
		}

		final ObjectClass_Definition myClass = myGovernor.getRefdLast(timestamp, null);
		final ObjectSet_definition refdLast = getRefdLast(timestamp, null);
		final ObjectClass_Definition refdClass = refdLast.getMyGovernor().getRefdLast(timestamp, null);
		if (myClass != refdClass) {
			if (location != null && refdClass != null && myClass != null) {
				location.reportSemanticError(MessageFormat.format(MISMATCH, myClass.getFullName(), refdClass.getFullName()));
			}
			osReferenced = new ObjectSet_definition();
			osReferenced.setMyGovernor(myGovernor);
			osReferenced.check(timestamp);
		}
	}

	@Override
	/** {@inheritDoc} */
	public int getNofObjects() {
		if (null == lastTimeChecked) {
			check(CompilationTimeStamp.getBaseTimestamp());
		}

		if (null == referencedLast) {
			return 0;
		}

		return referencedLast.getNofObjects();
	}

	@Override
	/** {@inheritDoc} */
	public ASN1Object getObjectByIndex(final int index) {
		if (null == lastTimeChecked) {
			check(CompilationTimeStamp.getBaseTimestamp());
		}

		if (null == referencedLast) {
			return null;
		}

		return referencedLast.getObjectByIndex(index);
	}

	@Override
	/** {@inheritDoc} */
	public void accept(final ObjectSetElement_Visitor visitor) {
		visitor.visitObjectSetReferenced(this);

	}

	/*
	 * public void set_fullname_ose(StringChainBuilder fullname) {
	 * setFullName(fullname); }
	 */

	@Override
	/** {@inheritDoc} */
	public void setMyScopeOse(final Scope scope) {
		setMyScope(scope);
	}

	@Override
	/** {@inheritDoc} */
	public void setGenNameOse(final String prefix, final String suffix) {
		setGenName(prefix, suffix);
	}

	@Override
	/** {@inheritDoc} */
	public void accept(final ObjectSetElementVisitor_objectCollector visitor) {
		visitor.visitObjectSet(this, false);
	}

	@Override
	/** {@inheritDoc} */
	public void addDeclaration(final DeclarationCollector declarationCollector, final int index) {
		if (null == lastTimeChecked) {
			check(CompilationTimeStamp.getBaseTimestamp());
		}

		if (null != osReferenced) {
			osReferenced.addDeclaration(declarationCollector, index);
		}
	}

	@Override
	/** {@inheritDoc} */
	public void addProposal(final ProposalCollector propCollector, final int index) {
		if (null == lastTimeChecked) {
			check(CompilationTimeStamp.getBaseTimestamp());
		}

		if (null != osReferenced) {
			osReferenced.addProposal(propCollector, index);
		}
	}

	@Override
	/** {@inheritDoc} */
	public boolean memberAccept(final ASTVisitor v) {
		if (reference != null && !reference.accept(v)) {
			return false;
		}
		return true;
	}

	@Override
	/** {@inheritDoc} */
	public void generateCode( final JavaGenData aData) {
		final ObjectSet_definition last = getRefdLast(CompilationTimeStamp.getBaseTimestamp(), null);
		if (myScope.getModuleScopeGen() == last.getMyScope().getModuleScopeGen()) {
			last.generateCode(aData);
		}
	}
}
