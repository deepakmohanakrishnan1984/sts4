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

import static org.springframework.ide.vscode.commons.yaml.ast.NodeUtil.asScalar;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.ide.vscode.commons.languageserver.reconcile.IProblemCollector;
import org.springframework.ide.vscode.commons.languageserver.util.DocumentRegion;
import org.springframework.ide.vscode.commons.util.ExceptionUtil;
import org.springframework.ide.vscode.commons.util.IntegerRange;
import org.springframework.ide.vscode.commons.util.Log;
import org.springframework.ide.vscode.commons.util.StringUtil;
import org.springframework.ide.vscode.commons.util.ValueParser;
import org.springframework.ide.vscode.commons.util.text.IDocument;
import org.springframework.ide.vscode.commons.yaml.ast.NodeUtil;
import org.springframework.ide.vscode.commons.yaml.ast.YamlFileAST;
import org.springframework.ide.vscode.commons.yaml.path.YamlPath;
import org.springframework.ide.vscode.commons.yaml.path.YamlPathSegment;
import org.springframework.ide.vscode.commons.yaml.schema.ASTDynamicSchemaContext;
import org.springframework.ide.vscode.commons.yaml.schema.DynamicSchemaContext;
import org.springframework.ide.vscode.commons.yaml.schema.YType;
import org.springframework.ide.vscode.commons.yaml.schema.YTypeUtil;
import org.springframework.ide.vscode.commons.yaml.schema.YTypedProperty;
import org.springframework.ide.vscode.commons.yaml.schema.YamlSchema;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeId;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;

public class SchemaBasedYamlASTReconciler implements YamlASTReconciler {

	private final IProblemCollector problems;
	private final YamlSchema schema;
	private final YTypeUtil typeUtil;

	public SchemaBasedYamlASTReconciler(IProblemCollector problems, YamlSchema schema) {
		this.problems = problems;
		this.schema = schema;
		this.typeUtil = schema.getTypeUtil();
	}

	@Override
	public void reconcile(YamlFileAST ast) {
		List<Node> nodes = ast.getNodes();
		IntegerRange expectedDocs = schema.expectedNumberOfDocuments();
		if (expectedDocs.isInRange(nodes.size())) {
			if (nodes!=null && !nodes.isEmpty()) {
				for (int i = 0; i < nodes.size(); i++) {
					Node node = nodes.get(i);
					reconcile(ast.getDocument(), new YamlPath(YamlPathSegment.valueAt(i)), node, schema.getTopLevelType());
				}
			}
		} else {
			//wrong number of documents in the file. Figure out a good error message.
			if (nodes.isEmpty()) {
				problem(allOf(ast.getDocument()), "'"+schema.getName()+"' must have at least some Yaml content");
			} else if (expectedDocs.isTooLarge(nodes.size())) {
				int upperBound = expectedDocs.getUpperBound();
				Node extraNode = nodes.get(upperBound);
				problem(dashesAtStartOf(ast, extraNode), "'"+schema.getName()+"' should not have more than "+upperBound+" Yaml Documents");
			} else if (expectedDocs.isTooSmall(nodes.size())) {
				int lowerBound = expectedDocs.getLowerBound();
				problem(endOf(ast.getDocument()), "'"+schema.getName()+"' should have at least "+lowerBound+" Yaml Documents");
			}
		}
	}

	private DocumentRegion dashesAtStartOf(YamlFileAST ast, Node node) {
		try {
			int start = node.getStartMark().getIndex();
			int end = node.getEndMark().getIndex();
			String text = ast.getDocument().textBetween(start, end);
			DocumentRegion textBefore = new DocumentRegion(ast.getDocument(), 0, start)
					.trimEnd(Pattern.compile("\\s*"));
			DocumentRegion dashes = textBefore.subSequence(textBefore.getLength()-3);
			if (dashes.toString().equals("---")) {
				return dashes;
			}
		} catch (Exception e) {
			Log.log(e);
		}
		//something unexpected... we couldn't find the '---'. So just mark the entire node.
		return allOf(ast, node);
	}

	private void reconcile(IDocument doc, YamlPath path, Node node, YType type) {
		if (type!=null) {
			DynamicSchemaContext schemaContext = new ASTDynamicSchemaContext(doc, path, node);
			type = typeUtil.inferMoreSpecificType(type, schemaContext);
			switch (getNodeId(node)) {
			case mapping:
				MappingNode map = (MappingNode) node;
				checkForDuplicateKeys(map);
				if (typeUtil.isMap(type)) {
					for (NodeTuple entry : map.getValue()) {
						String key = NodeUtil.asScalar(entry.getKeyNode());
						reconcile(doc, keyAt(path, key), entry.getKeyNode(), typeUtil.getKeyType(type));
						reconcile(doc, valueAt(path, key), entry.getValueNode(), typeUtil.getDomainType(type));
					}
				} else if (typeUtil.isBean(type)) {
					Map<String, YTypedProperty> beanProperties = typeUtil.getPropertiesMap(type, schemaContext);
					for (NodeTuple entry : map.getValue()) {
						Node keyNode = entry.getKeyNode();
						String key = NodeUtil.asScalar(keyNode);
						if (key==null) {
							expectScalar(node);
						} else {
							YTypedProperty prop = beanProperties.get(key);
							if (prop==null) {
								unknownBeanProperty(keyNode, type, key);
							} else {
								reconcile(doc, valueAt(path, key), entry.getValueNode(), prop.getType());
							}
						}
					}
				} else {
					expectTypeButFoundMap(type, node);
				}
				break;
			case sequence:
				SequenceNode seq = (SequenceNode) node;
				if (typeUtil.isSequencable(type)) {
					for (int i = 0; i < seq.getValue().size(); i++) {
						Node el = seq.getValue().get(i);
						reconcile(doc, valueAt(path, i), el, typeUtil.getDomainType(type));
					}
				} else {
					expectTypeButFoundSequence(type, node);
				}
				break;
			case scalar:
				if (typeUtil.isAtomic(type)) {
					ValueParser parser = typeUtil.getValueParser(type, schemaContext);
					if (parser!=null) {
						try {
							String value = NodeUtil.asScalar(node);
							if (value!=null) {
								parser.parse(value);
							}
						} catch (Exception e) {
							String msg = ExceptionUtil.getMessage(e);
							valueParseError(type, node, msg);
						}
					}
				} else {
					expectTypeButFoundScalar(type, node);
				}
				break;
			default:
				// other stuff we don't check
			}
		}
	}

	protected NodeId getNodeId(Node node) {
		NodeId id = node.getNodeId();
		if (id==NodeId.mapping && isMoustacheVar(node)) {
			return NodeId.scalar;
		}
		return id;
	}

	/**
	 * 'Moustache' variables look like `{{name}}` and unfortuately when
	 * parsed these will parse as a kind of map. But since these vars are meant to be replaced
	 * with some kind of string we should treat them as scalar instead.
	 * <p>
	 * This function recognizes a mapping node that actually is moustache var pattern.
	 */
	private boolean isMoustacheVar(Node node) {
		return NodeUtil.asScalar(debrace(debrace(node))) != null;
	}

	private Node debrace(Node _node) {
		MappingNode node = NodeUtil.asMapping(_node);
		if (node!=null && node.getFlowStyle() && node.getValue().size()==1) {
			NodeTuple entry = node.getValue().get(0);
			if ("".equals(NodeUtil.asScalar(entry.getValueNode()))) {
				return entry.getKeyNode();
			}
		}
		return null;
	}

	private YamlPath keyAt(YamlPath path, String key) {
		if (path!=null && key!=null) {
			return path.append(YamlPathSegment.keyAt(key));
		}
		return null;
	}

	private YamlPath valueAt(YamlPath path, int index) {
		if (path!=null) {
			return path.append(YamlPathSegment.valueAt(index));
		}
		return null;
	}

	private YamlPath valueAt(YamlPath path, String key) {
		if (path!=null && key!=null) {
			return path.append(YamlPathSegment.valueAt(key));
		}
		return null;
	}

	private void checkForDuplicateKeys(MappingNode node) {
		Set<String> duplicateKeys = new HashSet<>();
		Set<String> seenKeys = new HashSet<>();
		for (NodeTuple entry : node.getValue()) {
			String key = asScalar(entry.getKeyNode());
			if (key!=null) {
				if (!seenKeys.add(key)) {
					duplicateKeys.add(key);
				}
			}
		}
		if (!duplicateKeys.isEmpty()) {
			for (NodeTuple entry : node.getValue()) {
				Node keyNode = entry.getKeyNode();
				String key = asScalar(keyNode);
				if (key!=null && duplicateKeys.contains(key)) {
					problem(keyNode, "Duplicate key '"+key+"'");
				}
			}
		}
	}

	private void valueParseError(YType type, Node node, String parseErrorMsg) {
		if (!StringUtil.hasText(parseErrorMsg)) {
			parseErrorMsg= "Couldn't parse as '"+describe(type)+"'";
		}
		problem(node, parseErrorMsg);
	}

	private void unknownBeanProperty(Node keyNode, YType type, String name) {
		problem(keyNode, "Unknown property '"+name+"' for type '"+typeUtil.niceTypeName(type)+"'");
	}

	private void expectScalar(Node node) {
		problem(node, "Expecting a 'Scalar' node but got "+describe(node));
	}

	private String describe(Node node) {
		switch (node.getNodeId()) {
		case scalar:
			return "'"+((ScalarNode)node).getValue()+"'";
		case mapping:
			return "a 'Mapping' node";
		case sequence:
			return "a 'Sequence' node";
		case anchor:
			return "a 'Anchor' node";
		default:
			throw new IllegalStateException("Missing switch case");
		}
	}

	private void expectTypeButFoundScalar(YType type, Node node) {
		problem(node, "Expecting a '"+describe(type)+"' but found a 'Scalar'");
	}

	private void expectTypeButFoundSequence(YType type, Node node) {
		problem(node, "Expecting a '"+describe(type)+"' but found a 'Sequence'");
	}

	private void expectTypeButFoundMap(YType type, Node node) {
		problem(node, "Expecting a '"+describe(type)+"' but found a 'Map'");
	}

	private String describe(YType type) {
		if (typeUtil.isAtomic(type)) {
			return typeUtil.niceTypeName(type);
		}
		ArrayList<String> expectedNodeTypes = new ArrayList<>();
		if (typeUtil.isBean(type) || typeUtil.isMap(type)) {
			expectedNodeTypes.add("Map");
		}
		if (typeUtil.isSequencable(type)) {
			expectedNodeTypes.add("Sequence");
		}
		return StringUtil.collectionToDelimitedString(expectedNodeTypes, " or ");
	}

	private void problem(Node node, String msg) {
		problems.accept(YamlSchemaProblems.schemaProblem(msg, node));
	}

	private void problem(DocumentRegion region, String msg) {
		problems.accept(YamlSchemaProblems.schemaProblem(msg, region));
	}

	private DocumentRegion endOf(IDocument document) {
		return new DocumentRegion(document, document.getLength(), document.getLength());
	}

	private DocumentRegion allOf(IDocument doc) {
		return new DocumentRegion(doc, 0, doc.getLength());
	}
	private DocumentRegion allOf(YamlFileAST ast, Node node) {
		return new DocumentRegion(ast.getDocument(), node.getStartMark().getIndex(), node.getEndMark().getIndex());
	}


}
