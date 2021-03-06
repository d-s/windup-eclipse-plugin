/*******************************************************************************
 * Copyright (c) 2016 Red Hat, Inc.
 * Distributed under license by Red Hat, Inc. All rights reserved.
 * This program is made available under the terms of the
 * Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *   Red Hat, Inc. - initial API and implementation
 ******************************************************************************/
package org.jboss.tools.windup.ui.internal.services;

import static org.jboss.tools.windup.model.domain.WindupConstants.LAUNCH_COMPLETED;
import static org.jboss.tools.windup.model.domain.WindupConstants.MARKERS_CHANGED;
import static org.jboss.tools.windup.model.domain.WindupMarker.CLASSIFICATION;
import static org.jboss.tools.windup.model.domain.WindupMarker.COLUMN;
import static org.jboss.tools.windup.model.domain.WindupMarker.CONFIGURATION_ID;
import static org.jboss.tools.windup.model.domain.WindupMarker.DESCRIPTION;
import static org.jboss.tools.windup.model.domain.WindupMarker.EFFORT;
import static org.jboss.tools.windup.model.domain.WindupMarker.ELEMENT_ID;
import static org.jboss.tools.windup.model.domain.WindupMarker.HINT;
import static org.jboss.tools.windup.model.domain.WindupMarker.LENGTH;
import static org.jboss.tools.windup.model.domain.WindupMarker.LINE;
import static org.jboss.tools.windup.model.domain.WindupMarker.RULE_ID;
import static org.jboss.tools.windup.model.domain.WindupMarker.SEVERITY;
import static org.jboss.tools.windup.model.domain.WindupMarker.SOURCE_SNIPPET;
import static org.jboss.tools.windup.model.domain.WindupMarker.TITLE;
import static org.jboss.tools.windup.model.domain.WindupMarker.URI_ID;
import static org.jboss.tools.windup.model.domain.WindupMarker.WINDUP_CLASSIFICATION_MARKER_ID;
import static org.jboss.tools.windup.model.domain.WindupMarker.WINDUP_HINT_MARKER_ID;

import java.lang.reflect.InvocationTargetException;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.apache.commons.lang.ArrayUtils;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.e4.core.di.annotations.Creatable;
import org.eclipse.e4.core.di.annotations.Optional;
import org.eclipse.e4.core.services.events.IEventBroker;
import org.eclipse.e4.ui.di.UIEventTopic;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.dialogs.ProgressMonitorDialog;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.actions.WorkspaceModifyOperation;
import org.jboss.tools.windup.model.domain.ModelService;
import org.jboss.tools.windup.ui.WindupUIPlugin;
import org.jboss.tools.windup.ui.internal.Messages;
import org.jboss.tools.windup.ui.internal.explorer.MarkerUtil;
import org.jboss.tools.windup.windup.Classification;
import org.jboss.tools.windup.windup.ConfigurationElement;
import org.jboss.tools.windup.windup.Hint;
import org.jboss.tools.windup.windup.Input;
import org.jboss.tools.windup.windup.Issue;

import com.google.common.base.Objects;
import com.google.common.collect.Maps;

/**
 * Service for annotating eclipse {@link IResource}s with Windup's generated hints and classifications.
 */
@Singleton
@Creatable
public class MarkerService {
	
	@Inject private IEventBroker broker;
	@Inject private ModelService modelService;
	
	/**
	 * Creates markers for Windup migration issues. This is triggered after Windup has completed executing.  
	 */
	@Inject
	@Optional
	public void updateMarkers(@UIEventTopic(LAUNCH_COMPLETED) ConfigurationElement configuration) {
		try {
			WorkspaceModifyOperation op = new WorkspaceModifyOperation() {
				@Override
				protected void execute(IProgressMonitor monitor)
						throws CoreException, InvocationTargetException, InterruptedException {
					monitor.beginTask(Messages.generateIssues, getTotalIssueCount(configuration));
					monitor.subTask(Messages.generateIssues);
					createWindupMarkers(configuration, monitor);
				}
			};
			new ProgressMonitorDialog(Display.getDefault().getActiveShell()).run(false, false, op);
			broker.post(MARKERS_CHANGED, true);
		} catch (InvocationTargetException | InterruptedException e) {
			Display.getDefault().syncExec(() -> {
				MessageDialog.openError(Display.getDefault().getActiveShell(), 
						Messages.launchErrorTitle, Messages.markersCreateError);
			});
			WindupUIPlugin.log(e);
		}
	}
	
	public IMarker createFixedMarker(IMarker marker, Issue issue) {
		issue.setFixed(true);
		IMarker fixedMarker = MarkerService.createMarker(issue, marker.getResource());
		try {
			fixedMarker.setAttributes(marker.getAttributes());
			fixedMarker.setAttribute(IMarker.SEVERITY, IMarker.SEVERITY_INFO);
			marker.delete();
		} catch (CoreException e) {
			WindupUIPlugin.log(e);
		}
		return fixedMarker;
	}
	
	/**
	 * Returns the total number of markers that will be created. Used for reporting progress.
	 */
	public int getTotalIssueCount(ConfigurationElement configuration) {
		int count = 0;
		for (Input input : configuration.getInputs()) {
			if (input.getWindupResult() != null) {
				count += input.getWindupResult().getIssues().size();
			}
		}
		return count;
	}
	
	/**
	 * Creates markers for Windup migration issues contained in the provided configuration.
	 */
	public void createWindupMarkers(ConfigurationElement configuration, IProgressMonitor monitor) throws CoreException {
		int count = 0;
		for (Input input : configuration.getInputs()) {
			if (input.getWindupResult() != null) {
				for (Issue issue : input.getWindupResult().getIssues()) {
					IFile resource = ModelService.getIssueResource(issue);
					if (resource == null) {
						WindupUIPlugin.logErrorMessage("MarkerService:: No resource associated with issue file: " + issue.getFileAbsolutePath()); //$NON-NLS-1$
						continue;
					}
					createWindupMarker(issue, configuration, resource);
					monitor.worked(++count);
				}
			}
		}
	}
	
	public static IMarker createMarker(Issue issue, IResource resource) {
		String type = issue instanceof Classification ? WINDUP_CLASSIFICATION_MARKER_ID : WINDUP_HINT_MARKER_ID;
		try {
			return resource.createMarker(type);
		} catch (CoreException e) {
			WindupUIPlugin.log(e);
		}
		return null;
	}
	
	/**
	 * Helper method that actually creates the marker on the specified resource for the specified Windup migration issue.
	 */
	private void createWindupMarker(Issue issue, ConfigurationElement configuration, IResource resource) throws CoreException {
	
		IMarker marker = createMarker(issue, resource);
		marker.setAttribute(CONFIGURATION_ID, configuration.getName());
		
		IJavaElement element = JavaCore.create(resource);
		if (element != null) {
			marker.setAttribute(ELEMENT_ID, element.getHandleIdentifier());
		}
		marker.setAttribute(URI_ID, EcoreUtil.getURI(issue).toString());
		marker.setAttribute(IMarker.SEVERITY, MarkerUtil.convertSeverity(issue.getSeverity()));
		marker.setAttribute(SEVERITY, issue.getSeverity());
        marker.setAttribute(RULE_ID, issue.getRuleId());
        marker.setAttribute(EFFORT, issue.getEffort());
		
		if (issue instanceof Hint) {
			Hint hint = (Hint)issue;
			
			marker.setAttribute(IMarker.MESSAGE, hint.getTitle());
			marker.setAttribute(IMarker.LINE_NUMBER, hint.getLineNumber());
			
			marker.setAttribute(TITLE, hint.getTitle());
			marker.setAttribute(HINT, hint.getHint());
			marker.setAttribute(LINE, hint.getLineNumber());
			marker.setAttribute(COLUMN, hint.getColumn());
			marker.setAttribute(LENGTH, hint.getLength());
			
			marker.setAttribute(SOURCE_SNIPPET, hint.getSourceSnippet());
		}
		else {
			Classification classification = (Classification)issue;
			marker.setAttribute(IMarker.MESSAGE, classification.getClassification());
			marker.setAttribute(CLASSIFICATION, classification.getClassification());
			marker.setAttribute(DESCRIPTION, classification.getDescription());
			
			marker.setAttribute(IMarker.LINE_NUMBER, 1);
			marker.setAttribute(IMarker.CHAR_START, 0);
			marker.setAttribute(IMarker.CHAR_END, 0);
		}
        marker.setAttribute(IMarker.USER_EDITABLE, false);
	}
	
	/**
	 * Deletes all Windup markers on all projects within the workspace.
	 */
	public void deleteAllWindupMarkers() {
		for (IProject input : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
			if (input.isAccessible()) {
				deleteWindupMarkers(input);
			}
		}
		broker.post(MARKERS_CHANGED, true);
	}
	
	/**
	 * Deletes all Windup markers assigned to the specified resource.
	 */
	private void deleteWindupMarkers(IResource input) {
		try {
			input.deleteMarkers(WINDUP_HINT_MARKER_ID, true, IResource.DEPTH_INFINITE);
			input.deleteMarkers(WINDUP_CLASSIFICATION_MARKER_ID, true, IResource.DEPTH_INFINITE);
		} catch (CoreException e) {
			WindupUIPlugin.log(e);
		}
	}
	
	/**
	 * Returns issue/marker pairs associated with the specified resource. 
	 */
	public Map<Issue, IMarker> buildIssueMarkerMap(IResource resource) {
		Map<Issue, IMarker> map = Maps.newHashMap();
		try {
			IMarker[] markers = resource.findMarkers(WINDUP_HINT_MARKER_ID, true, IResource.DEPTH_INFINITE);
			for (IMarker marker : markers) {
				map.put(modelService.findIssue(marker), marker);
			}
		} catch (CoreException e) {
			WindupUIPlugin.log(e);
		}
		return map;
	}
	
	public static IMarker findMarker(IResource resource, Issue issue, ModelService modelService) {
		IMarker result = null;
		try {
			IMarker[] hints = resource.findMarkers(WINDUP_HINT_MARKER_ID, true, IResource.DEPTH_INFINITE);
			IMarker[] classifications = resource.findMarkers(WINDUP_CLASSIFICATION_MARKER_ID, true, IResource.DEPTH_INFINITE);
			IMarker[] markers = (IMarker[]) ArrayUtils.addAll(hints, classifications);
			for (IMarker marker : markers) {
				Issue markerIssue = modelService.findIssue(marker);
				if (Objects.equal(issue, markerIssue)) {
					return marker;
				}
			}
		} catch (CoreException e) {
			WindupUIPlugin.log(e);
		}
		return result;
	}
}
