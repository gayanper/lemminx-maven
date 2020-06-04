/*******************************************************************************
 * Copyright (c) 2019-2020 Red Hat Inc. and others.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.lemminx.maven;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.annotation.Nonnull;

import org.apache.maven.model.building.ModelProblem;
import org.apache.maven.model.building.ModelProblem.Severity;
import org.apache.maven.plugin.MavenPluginManager;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.lemminx.dom.DOMDocument;
import org.eclipse.lemminx.dom.DOMElement;
import org.eclipse.lemminx.dom.DOMNode;
import org.eclipse.lemminx.services.extensions.diagnostics.IDiagnosticsParticipant;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.jsonrpc.CancelChecker;

public class MavenDiagnosticParticipant implements IDiagnosticsParticipant {

	private MavenProjectCache projectCache;
	MavenPluginManager pluginManager;
	private final RepositorySystemSession repoSession;

	public MavenDiagnosticParticipant(MavenProjectCache projectCache, MavenPluginManager pluginManager, RepositorySystemSession repoSession) {
		this.projectCache = projectCache;
		this.pluginManager = pluginManager;
		this.repoSession = repoSession;
	}

	@Override
	public void doDiagnostics(DOMDocument xmlDocument, List<Diagnostic> diagnostics, CancelChecker monitor) {
		if (!MavenPlugin.match(xmlDocument)) {
			  return;
		}
		
		projectCache.getProblemsFor(xmlDocument).stream().map(this::toDiagnostic).forEach(diagnostics::add);
		DOMElement documentElement = xmlDocument.getDocumentElement();
		HashMap<String, Function<DiagnosticRequest, Optional<List<Diagnostic>>>> tagDiagnostics = configureDiagnosticFunctions();

		Deque<DOMNode> nodes = new ArrayDeque<>();
		for (DOMNode node : documentElement.getChildren()) {
			nodes.push(node);
		}
		while (!nodes.isEmpty()) {
			DOMNode node = nodes.pop();
			for (Entry<String, Function<DiagnosticRequest, Optional<List<Diagnostic>>>> entry : tagDiagnostics
					.entrySet()) {
				if (node.getLocalName() != null && node.getLocalName().equals(entry.getKey())) {
					entry.getValue().apply(new DiagnosticRequest(node, xmlDocument))
							.ifPresent(diagnosticList -> {
								// Don't add a diagnostic if it already exists
								diagnostics.addAll(
										diagnosticList.stream().filter(diagnostic -> !diagnostics.contains(diagnostic))
												.collect(Collectors.toList()));
							});
				}
			}
			if (node.hasChildNodes()) {
				for (DOMNode childNode : node.getChildren()) {
					nodes.push(childNode);
				}
			}
		}
	}

	private HashMap<String, Function<DiagnosticRequest, Optional<List<Diagnostic>>>> configureDiagnosticFunctions() {
		PluginValidator pluginValidator = new PluginValidator(projectCache, repoSession, pluginManager);

		Function<DiagnosticRequest, Optional<List<Diagnostic>>> validatePluginConfiguration = pluginValidator::validateConfiguration;
		Function<DiagnosticRequest, Optional<List<Diagnostic>>> validatePluginGoal = pluginValidator::validateGoal;

		HashMap<String, Function<DiagnosticRequest, Optional<List<Diagnostic>>>> tagDiagnostics = new HashMap<>();
		tagDiagnostics.put("configuration", validatePluginConfiguration);
		tagDiagnostics.put("goal", validatePluginGoal);
		return tagDiagnostics;
	}

	private Diagnostic toDiagnostic(@Nonnull ModelProblem problem) {
		Diagnostic diagnostic = new Diagnostic();
		diagnostic.setMessage(problem.getMessage());
		diagnostic.setSeverity(toDiagnosticSeverity(problem.getSeverity()));
		diagnostic.setRange(new Range(new Position(problem.getLineNumber() - 1, problem.getColumnNumber() - 1),
				new Position(problem.getLineNumber() - 1, problem.getColumnNumber())));
		return diagnostic;
	}

	private DiagnosticSeverity toDiagnosticSeverity(Severity severity) {
		switch (severity) {
		case ERROR:
		case FATAL:
			return DiagnosticSeverity.Error;
		case WARNING:
			return DiagnosticSeverity.Warning;
		}
		return DiagnosticSeverity.Information;
	}

}
