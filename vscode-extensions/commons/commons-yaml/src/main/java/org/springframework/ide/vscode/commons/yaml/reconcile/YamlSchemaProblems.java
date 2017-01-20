/*******************************************************************************
 * Copyright (c) 2016-2017 Pivotal, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Pivotal, Inc. - initial API and implementation
 *******************************************************************************/

package org.springframework.ide.vscode.commons.yaml.reconcile;

import org.springframework.ide.vscode.commons.languageserver.reconcile.ProblemSeverity;
import org.springframework.ide.vscode.commons.languageserver.reconcile.ProblemType;
import org.springframework.ide.vscode.commons.languageserver.reconcile.ReconcileProblem;
import org.springframework.ide.vscode.commons.languageserver.reconcile.ReconcileProblemImpl;
import org.springframework.ide.vscode.commons.languageserver.util.DocumentRegion;
import org.springframework.ide.vscode.commons.yaml.ast.YamlFileAST;
import org.yaml.snakeyaml.nodes.Node;

/**
 * Methods for creating reconciler problems for Schema based reconciler implementation.
 *
 * @author Kris De Volder
 */
public class YamlSchemaProblems {

	private static final ProblemType SCHEMA_PROBLEM = problemType("YamlSchemaProblem");
	private static final ProblemType SYNTAX_PROBLEM = problemType("YamlSyntaxProblem");

	private static ProblemType problemType(final String typeName) {
		return new ProblemType() {
			@Override
			public String toString() {
				return typeName;
			}
			@Override
			public ProblemSeverity getDefaultSeverity() {
				return ProblemSeverity.ERROR;
			}
			@Override
			public String getCode() {
				return typeName;
			}
		};
	}

	public static ReconcileProblem syntaxProblem(String msg, int offset, int len) {
		return new ReconcileProblemImpl(SYNTAX_PROBLEM, msg, offset, len);
	}

	public static ReconcileProblem schemaProblem(String msg, Node node) {
		int start = node.getStartMark().getIndex();
		int end = node.getEndMark().getIndex();
		return new ReconcileProblemImpl(SCHEMA_PROBLEM, msg, start, end-start);
	}

	public static ReconcileProblem schemaProblem(String msg, DocumentRegion node) {
		return new ReconcileProblemImpl(SCHEMA_PROBLEM, msg, node.getStart(), node.getLength());
	}

}
