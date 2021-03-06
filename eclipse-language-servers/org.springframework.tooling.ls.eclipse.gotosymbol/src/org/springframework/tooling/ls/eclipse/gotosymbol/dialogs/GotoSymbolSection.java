/*******************************************************************************
 * Copyright (c) 2016, 2019, 2020 Rogue Wave Software Inc. and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *  Michał Niewrzał (Rogue Wave Software Inc.) - initial implementation
 *  Kris De Volder (Pivotal Inc) - Copied and adapted 
 *******************************************************************************/
package org.springframework.tooling.ls.eclipse.gotosymbol.dialogs;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.resource.JFaceColors;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.viewers.ColumnViewerToolTipSupport;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.StyledCellLabelProvider;
import org.eclipse.jface.viewers.StyledString;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.ViewerCell;
import org.eclipse.lsp4e.LSPEclipseUtils;
import org.eclipse.lsp4e.outline.SymbolsLabelProvider;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.KeyAdapter;
import org.eclipse.swt.events.KeyEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.TreeItem;
import org.springframework.tooling.ls.eclipse.gotosymbol.dialogs.GotoSymbolDialogModel.Match;
import org.springsource.ide.eclipse.commons.core.util.FuzzyMatcher;
import org.springsource.ide.eclipse.commons.livexp.core.UIValueListener;
import org.springsource.ide.eclipse.commons.livexp.ui.Disposable;
import org.springsource.ide.eclipse.commons.livexp.ui.IPageWithSections;
import org.springsource.ide.eclipse.commons.livexp.ui.Stylers;
import org.springsource.ide.eclipse.commons.livexp.ui.WizardPageSection;
import org.springsource.ide.eclipse.commons.livexp.ui.util.SwtConnect;

@SuppressWarnings("restriction")
public class GotoSymbolSection extends WizardPageSection {

	private static class SymbolsContentProvider implements ITreeContentProvider {
		
		@Override
		public Object[] getChildren(Object parentElement) {
			return null;
		}

		@Override
		public Object getParent(Object element) {
			return null;
		}

		@Override
		public boolean hasChildren(Object element) {
			return false;
		}

		@Override
		public Object[] getElements(Object inputElement) {
			if (inputElement instanceof GotoSymbolDialogModel) {
				GotoSymbolDialogModel model = (GotoSymbolDialogModel) inputElement;
				return model.getSymbols().getValue().toArray();
			}
			return null;
		}
	}

	private  class GotoSymbolsLabelProvider extends StyledCellLabelProvider {
		
		private Stylers stylers;
		private SymbolsLabelProvider symbolsLabelProvider;
		
		public GotoSymbolsLabelProvider(Font base) {
			stylers = new Stylers(base);
			boolean showSymbolsLabelProviderLocation  = false; /* dont show full location. we show relative location in our own implementation below */
			boolean showKindInformation = false;
			symbolsLabelProvider = new SymbolsLabelProvider(showSymbolsLabelProviderLocation , showKindInformation) {
				@Override
				protected int getMaxSeverity(IResource resource, IDocument doc, Range range)
						throws CoreException, BadLocationException {
					int maxSeverity = -1;
					for (IMarker marker : resource.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_ZERO)) {
						int offset = marker.getAttribute(IMarker.CHAR_START, -1);
						if (offset != -1) {
							maxSeverity = Math.max(maxSeverity, marker.getAttribute(IMarker.SEVERITY, -1));
						}
					}
					return maxSeverity;
				}
			};
		}
		
		@Override
		public Color getToolTipBackgroundColor(Object object) {
			return JFaceColors.getInformationViewerBackgroundColor(Display.getDefault());
		}
		
		@Override
		public Color getToolTipForegroundColor(Object object) {
			return JFaceColors.getInformationViewerForegroundColor(Display.getDefault());
		}
		
		@Override
		public String getToolTipText(Object element) {
			if (element instanceof Match) {
				SymbolInformation si = getSymbolInformation((Match<?>)element);
				if (si!=null) {
					return si.getName();
				}
			}
			return null;
		}
	
		@Override
		public void update(ViewerCell cell) {
			super.update(cell);
			Object obj = cell.getElement();
			if (obj instanceof Match) {
				Match<?> match = (Match<?>) obj;
				cell.setImage(symbolsLabelProvider.getImage(match.value));
				StyledString styledString = getStyledText(match);
				cell.setText(styledString.getString());
				cell.setStyleRanges(styledString.getStyleRanges());
				cell.getControl().redraw(); 
				//^^^ Sigh... Yes, this is needed. It seems SWT/Jface isn't smart enough to itself figure out that if 
				//the styleranges change a redraw is needed to make the change visible.
			} else {
				super.update(cell);
			}
		}

		private StyledString getStyledText(Match<?> element) {
			SymbolInformation symbolInformation = getSymbolInformation(element);
			if (symbolInformation != null) {
				String name = symbolInformation.getName();
				StyledString s = new StyledString(name);
				Collection<IRegion> highlights = FuzzyMatcher.highlights(element.query, name.toLowerCase());
				for (IRegion hl : highlights) {
					s.setStyle(hl.getOffset(), hl.getLength(), stylers.bold());
				}
				String locationText = getSymbolLocationText(symbolInformation);
				if (locationText != null) {
					s = s.append(locationText, stylers.italicColoured(SWT.COLOR_DARK_GRAY));
				}
				return s;
			} else {
				return symbolsLabelProvider.getStyledText(element.value);
			}
		}	

		@Override
		public void dispose() {
			stylers.dispose();
			symbolsLabelProvider.dispose();
			super.dispose();
		}
		
		protected String getSymbolLocationText(SymbolInformation symbol) {
			Optional<String> location = GotoSymbolSection.this
					.getSymbolLocation(symbol);
			if (location.isPresent()) {
				return " -- [" + location.get() + "]";
			}
			return null;
		}
	}

	private final GotoSymbolDialogModel model;


	public GotoSymbolSection(IPageWithSections owner, GotoSymbolDialogModel model) {
		super(owner);
		this.model = model;
	}

	@Override
	public void createContents(Composite dialogArea) {
		List<Disposable> disposables = new ArrayList<>();
		dialogArea.addDisposeListener(de -> {
			for (Disposable d : disposables) {
				d.dispose();
			}
		});	
		
		//Search box:
		Text pattern = new Text(dialogArea, SWT.SINGLE | SWT.BORDER | SWT.SEARCH | SWT.ICON_CANCEL);
//		pattern.getAccessible().addAccessibleListener(new AccessibleAdapter() {
//			public void getName(AccessibleEvent e) {
//				e.result = LegacyActionTools.removeMnemonics(headerLabel)
//						.getText());
//			}
//		});
		pattern.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		pattern.setMessage(model.getSearchBoxHintMessage());
		SwtConnect.connect(pattern, model.getSearchBox());

		//Tree viewer with results
		TreeViewer viewer = new TreeViewer(dialogArea, SWT.SINGLE);
		ColumnViewerToolTipSupport.enableFor(viewer);
		GridDataFactory.fillDefaults().grab(true, true).applyTo(viewer.getControl());
		viewer.setContentProvider(new SymbolsContentProvider());
		viewer.setLabelProvider(new GotoSymbolsLabelProvider(viewer.getTree().getFont()));
		viewer.setUseHashlookup(true);
		disposables.add(model.getSymbols().onChange(UIValueListener.from((e, v) -> {
			if (!viewer.getControl().isDisposed()) viewer.refresh();
		})));
//TODO: somehow show selection in local file, (but not in other file ?)
//		viewer.addSelectionChangedListener(event -> {
//			IStructuredSelection selection = (IStructuredSelection) event.getSelection();
//			if (selection.isEmpty()) {
//				return;
//			}
//			SymbolInformation symbolInformation = (SymbolInformation) selection.getFirstElement();
//			Location location = symbolInformation.getLocation();
//
//			IResource targetResource = LSPEclipseUtils.findResourceFor(location.getUri());
//			if (targetResource == null) {
//				return;
//			}
//			IDocument targetDocument = FileBuffers.getTextFileBufferManager()
//			        .getTextFileBuffer(targetResource.getFullPath(), LocationKind.IFILE).getDocument();
//			if (targetDocument != null) {
//				try {
//					int offset = LSPEclipseUtils.toOffset(location.getRange().getStart(), targetDocument);
//					int endOffset = LSPEclipseUtils.toOffset(location.getRange().getEnd(), targetDocument);
//					fTextEditor.selectAndReveal(offset, endOffset - offset);
//				} catch (BadLocationException e) {
//					LanguageServerPlugin.logError(e);
//				}
//			}
//		});
		installWidgetListeners(pattern, viewer);

		//Status label
		StyledText statusLabel = new StyledText(dialogArea, SWT.NONE);
		// Allow for some extra space for highlight fonts
		statusLabel.setLeftMargin(3);
		statusLabel.setBottomMargin(2);
		statusLabel.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
		
		Stylers stylers = new Stylers(dialogArea.getFont());
		disposables.add(stylers);
		
		SwtConnect.connectHighlighted(stylers.bold(), statusLabel, model.getStatus(), Duration.ofMillis(500));
		viewer.setInput(model);
	}

	private void installWidgetListeners(Text pattern, TreeViewer list) {
		pattern.addKeyListener(new KeyAdapter() {
			public void keyPressed(KeyEvent e) {
				if (e.keyCode == SWT.ARROW_DOWN) {
					if (list.getTree().getItemCount() > 0) {
						list.getTree().setFocus();
						TreeItem[] items = list.getTree().getItems();
						if (items!=null && items.length>0) {
							list.getTree().setSelection(items[0]);
							//programatic selection may not fire selection events so...
							list.getTree().notifyListeners(SWT.Selection,
									new Event());
						}
					}
				} else if (e.character == '\r') {
					performOk(list);
				}
			}
		});

		list.getTree().addKeyListener(new KeyAdapter() {
			public void keyPressed(KeyEvent e) {

				if (e.keyCode == SWT.ARROW_UP && (e.stateMask & SWT.SHIFT) == 0
						&& (e.stateMask & SWT.CTRL) == 0) {
					StructuredSelection selection = (StructuredSelection) list
							.getSelection();

					if (selection.size() == 1) {
						Object element = selection.getFirstElement();
						if (element.equals(getFirstElement(list))) {
							pattern.setFocus();
							list.setSelection(new StructuredSelection());
							list.getTree().notifyListeners(SWT.Selection,
									new Event());
						}

					}
				} else if (e.character == '\r') {
					performOk(list);
				}

//				if (e.keyCode == SWT.ARROW_DOWN
//						&& (e.stateMask & SWT.SHIFT) != 0
//						&& (e.stateMask & SWT.CTRL) != 0) {
//
//					list.getTree().notifyListeners(SWT.Selection, new Event());
//				}

			}
		});
		
		list.addDoubleClickListener(e -> performOk(list));
	}
	
	private void performOk(TreeViewer list) {
		if (model.performOk(getTarget(list))) {
			close();
		}
	}

	/**
	 * Determine the 'target' for the dialog's action.
	 */
	private SymbolInformation getTarget(TreeViewer list) {
		ISelection sel = list.getSelection();
		if (sel instanceof IStructuredSelection) {
			IStructuredSelection ss = (IStructuredSelection) sel;
			Object selected = ss.getFirstElement();
			if (selected instanceof Match) {
				SymbolInformation si = getSymbolInformation((Match<?>) selected);
				if (si!=null) {
					return si;
				}
			}
		}
		//No element selected, target the first element in the list instead.
		//This allows user to execute the action without explicitly selecting an element.
		return getFirstElement(list);
	}

	private SymbolInformation getFirstElement(TreeViewer list) {
		TreeItem[] items = list.getTree().getItems();
		if (items!=null && items.length>0) {
			TreeItem item = items[0];
			Object data = item.getData();
			if (data instanceof Match) {
				SymbolInformation si = getSymbolInformation((Match<?>) data);
				if (si!=null) {
					return si;
				}
			}
		}
		return null;
	}

	private SymbolInformation getSymbolInformation(Match<?> element) {
		if (element.value instanceof SymbolInformation) {
			return (SymbolInformation) element.value;
		}
		if (element.value instanceof Either) {
			Either<?,?> either = (Either<?, ?>)element.value;
			if (either.isLeft()) {
				Object left = either.getLeft();
				if (left instanceof SymbolInformation) {
					return (SymbolInformation) left;
				}
			}
		}
		return null;
	}
	
	private Optional<String> getSymbolLocation(SymbolInformation symbolInformation) {
		String val = null;

		if (!model.fromFileProvider(symbolInformation)) {
			Location location = symbolInformation.getLocation();

			IResource targetResource = LSPEclipseUtils.findResourceFor(location.getUri());
			if (targetResource != null && targetResource.getFullPath() != null) {
				val = targetResource.getFullPath().toString();
			}	
		}

		return val != null ? Optional.of(val) : Optional.empty();
	}
	
}
