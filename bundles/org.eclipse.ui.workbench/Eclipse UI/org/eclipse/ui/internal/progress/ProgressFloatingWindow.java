/*******************************************************************************
 * Copyright (c) 2004 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials 
 * are made available under the terms of the Common Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/cpl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.ui.internal.progress;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.KeyAdapter;
import org.eclipse.swt.events.KeyEvent;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.FontMetrics;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Region;
import org.eclipse.swt.layout.FormAttachment;
import org.eclipse.swt.layout.FormData;
import org.eclipse.swt.layout.FormLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Layout;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;
import org.eclipse.swt.widgets.Widget;

import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.viewers.IContentProvider;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.TableViewer;

import org.eclipse.ui.internal.AssociatedWindow;
import org.eclipse.ui.internal.WorkbenchWindow;
/**
 * The ProgressFloatingWindow is a window that opens next to an animation item.
 */
class ProgressFloatingWindow extends AssociatedWindow {
	TableViewer viewer;
	WorkbenchWindow window;
	final int borderSize = 1;
	/**
	 * Create a new instance of the receiver.
	 * 
	 * @param workbenchWindow
	 *            the workbench window.
	 * @param associatedControl
	 *            the associated control.
	 */
	ProgressFloatingWindow(WorkbenchWindow workbenchWindow,
			Control associatedControl) {
		super(workbenchWindow.getShell(), associatedControl,
				AssociatedWindow.TRACK_OUTER_BOTTOM_RHS);
		this.window = workbenchWindow;
		//Workaround for Bug 50917
		if ("carbon".equals(SWT.getPlatform())) //$NON-NLS-1$
			setShellStyle(SWT.NO_TRIM | SWT.ON_TOP);
		else
			setShellStyle(SWT.NO_TRIM);
	}
	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.jface.window.Window#getLayout()
	 */
	protected Layout getLayout() {
		FormLayout layout = new FormLayout();
		layout.marginHeight = 5;
		layout.marginWidth = 5;
		return layout;
	}
	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.ui.internal.AssociatedWindow#configureShell(org.eclipse.swt.widgets.Shell)
	 */
	protected void configureShell(Shell newShell) {
		super.configureShell(newShell);
		newShell.setLayout(getLayout());
		setBackground(newShell);
	}
	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.jface.window.Window#createContents(org.eclipse.swt.widgets.Composite)
	 */
	protected Control createContents(Composite root) {
		Control buttonBar = createButtons(root);
		viewer = new TableViewer(root, SWT.MULTI) {
			/*
			 * * (non-Javadoc)
			 * 
			 * @see org.eclipse.jface.viewers.TableViewer#doUpdateItem(org.eclipse.swt.widgets.Widget,
			 *      java.lang.Object, boolean)
			 */
			protected void doUpdateItem(Widget widget, Object element,
					boolean fullMap) {
				super.doUpdateItem(widget, element, fullMap);
				adjustSize();
			}
		};
		viewer.setUseHashlookup(true);
		viewer.setSorter(ProgressManagerUtil.getProgressViewerSorter());
		setBackground(viewer.getControl());
		FormData tableData = new FormData();
		tableData.left = new FormAttachment(0);
		tableData.right = new FormAttachment(buttonBar, 0);
		tableData.top = new FormAttachment(0);
		viewer.getTable().setLayoutData(tableData);
		initContentProvider();
		viewer.setLabelProvider(viewerLabelProvider());
		root.addListener(SWT.Traverse, new Listener() {
			public void handleEvent(Event event) {
				if (event.detail == SWT.TRAVERSE_ESCAPE) {
					event.doit = false;
				}
			}
		});
		viewer.getTable().addKeyListener(new KeyAdapter() {
			public void keyPressed(KeyEvent e) {
				if (e.keyCode == SWT.DEL) {
					TableItem[] tableItem = viewer.getTable().getSelection();
					for (int index = 0; index < tableItem.length; index++)
						((JobTreeElement) tableItem[index].getData()).cancel();
					viewer.refresh();
				}
			}
		});
		return viewer.getControl();
	}
	/**
	 * Return the label provider for the viewer.
	 * 
	 * @return LabelProvider the shortened text.
	 */
	private LabelProvider viewerLabelProvider() {
		return new LabelProvider() {
			private String ellipsis = ProgressMessages
					.getString("ProgressFloatingWindow.EllipsisValue"); //$NON-NLS-1$
			/*
			 * (non-Javadoc)
			 * 
			 * @see org.eclipse.jface.viewers.LabelProvider#getText(java.lang.Object)
			 */
			public String getText(Object element) {
				JobTreeElement info = (JobTreeElement) element;
				return shortenText(info.getCondensedDisplayString(), viewer
						.getControl().getDisplay());
			}
			/**
			 * Shorten the given text <code>t</code> so that its length
			 * doesn't exceed the given width. The default implementation
			 * replaces characters in the center of the original string with an
			 * ellipsis ("..."). Override if you need a different strategy.
			 */
			protected String shortenText(String textValue, Display display) {
				if (textValue == null)
					return null;
				GC gc = new GC(display);
				int maxWidth = viewer.getControl().getBounds().width - 25;
				if (gc.textExtent(textValue).x < maxWidth) {
					gc.dispose();
					return textValue;
				}
				int length = textValue.length();
				int ellipsisWidth = gc.textExtent(ellipsis).x;
				int pivot = length / 2;
				int start = pivot;
				int end = pivot + 1;
				while (start >= 0 && end < length) {
					String s1 = textValue.substring(0, start);
					String s2 = textValue.substring(end, length);
					int l1 = gc.textExtent(s1).x;
					int l2 = gc.textExtent(s2).x;
					if (l1 + ellipsisWidth + l2 < maxWidth) {
						gc.dispose();
						return s1 + ellipsis + s2;
					}
					start--;
					end++;
				}
				gc.dispose();
				return textValue;
			}
		};
	}
	/**
	 * Adjust the size of the viewer.
	 */
	private void adjustSize() {
		getShell().setSize(getMaximumSize(viewer.getTable().getDisplay()));
		addRoundBorder(borderSize);
		moveShell(getShell(), AssociatedWindow.ALWAYS_VISIBLE);
	}
	/**
	 * Get the maximum size of the window based on the display.
	 * 
	 * @param display
	 * @return int
	 */
	private Point getMaximumSize(Display display) {
		GC gc = new GC(viewer.getTable());
		FontMetrics fm = gc.getFontMetrics();
		int charWidth = fm.getAverageCharWidth();
		int charHeight = fm.getHeight();
		int maxWidth = display.getBounds().width / 6;
		int maxHeight = display.getBounds().height / 6;
		int fontWidth = charWidth * 34;
		int fontHeight = charHeight * 4;
		if (maxWidth < fontWidth)
			fontWidth = maxWidth;
		if (maxHeight < fontHeight)
			fontHeight = maxHeight;
		gc.dispose();
		return new Point(fontWidth, fontHeight);
	}
	/**
	 * Set the content provider for the viewer.
	 */
	protected void initContentProvider() {
		IContentProvider provider = new ProgressTableContentProvider(viewer);
		viewer.setContentProvider(provider);
		viewer.setInput(provider);
	}
	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.jface.window.Window#close()
	 */
	public boolean close() {
		
		Shell shellToClose = getShell();
		if (shellToClose == null || shellToClose.isDisposed())
			return super.close();
		Region oldRegion = shellToClose.getRegion();
		boolean result = super.close();
		if (result && oldRegion != null)
			oldRegion.dispose();
		return result;
	}
	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.jface.window.Window#open()
	 */
	public int open() {
		if (getShell() == null) {
			create();
		}
		constrainShellSize();
		animateVertical();
		getShell().setVisible(true);
		return getReturnCode();
	}
	/**
	 * Animate the shell vertically.
	 */
	private void animateVertical() {
		if (getShell() == null || getShell().isDisposed())
			return;
		final int initShellSize = getShell().getSize().y;
		final int time = 10;
		if (getShell() == null || getShell().isDisposed())
			return;
		getShell().setSize(getShell().getSize().x, 0);
		final Runnable timer = new Runnable() {
			public void run() {
				if (getShell() == null || getShell().isDisposed())
					return;
				if (getShell().getSize().y == initShellSize)
					return;
				getShell().setSize(getShell().getSize().x,
						getShell().getSize().y + 1);
				getShell().getDisplay().timerExec(time, this);
			}
		};
		if (getShell() == null || getShell().isDisposed())
			return;
		getShell().getDisplay().timerExec(time, timer);
	}
	/**
	 * Set the background color of the control to the info background.
	 * 
	 * @param control
	 *            the shell's control.
	 */
	private void setBackground(Control control) {
		control.setBackground(control.getDisplay().getSystemColor(
				SWT.COLOR_INFO_BACKGROUND));
	}
	/**
	 * Create the buttons for the progress floating window.
	 * 
	 * @param parent
	 *            the parent composite.
	 */
	private Control createButtons(Composite parent) {
		ToolBar buttonBar = new ToolBar(parent, SWT.HORIZONTAL);
		setBackground(buttonBar);
		ToolItem minimize = new ToolItem(buttonBar, SWT.NONE);
		minimize
				.setImage(JFaceResources.getImage(ProgressManager.MINIMIZE_KEY));
		minimize.addSelectionListener(new SelectionAdapter() {
			/*
			 * (non-Javadoc)
			 * 
			 * @see org.eclipse.swt.events.SelectionAdapter#widgetSelected(org.eclipse.swt.events.SelectionEvent)
			 */
			public void widgetSelected(SelectionEvent e) {
				window.toggleFloatingWindow();
				//If the minimize failed to close the floating
				//window do a close anyways
				Shell remainingShell = getShell();
				if (remainingShell == null || remainingShell.isDisposed())
					return;
				close();
			}
		});
		minimize.setToolTipText(ProgressMessages
				.getString("ProgressFloatingWindow.CloseToolTip")); //$NON-NLS-1$
		createMaximizeButton(buttonBar);
		FormData barData = new FormData();
		barData.right = new FormAttachment(100);
		barData.top = new FormAttachment(0);
		buttonBar.setLayoutData(barData);
		return buttonBar;
	}
	/**
	 * Create the maximize button if there is a progress view we can open.
	 * 
	 * @param buttonBar
	 */
	private void createMaximizeButton(ToolBar buttonBar) {
		//If there is no progress view do not create the
		//button.
		if (ProgressManagerUtil.missingProgressView(window))
			return;
		ToolItem maximize = new ToolItem(buttonBar, SWT.NONE);
		maximize
				.setImage(JFaceResources.getImage(ProgressManager.MAXIMIZE_KEY));
		maximize.addSelectionListener(new SelectionAdapter() {
			/*
			 * (non-Javadoc)
			 * 
			 * @see org.eclipse.swt.events.SelectionAdapter#widgetSelected(org.eclipse.swt.events.SelectionEvent)
			 */
			public void widgetSelected(SelectionEvent e) {
				window.toggleFloatingWindow();
				ProgressManagerUtil.openProgressView(window);
			}
		});
		maximize.setToolTipText(ProgressMessages
				.getString("ProgressFloatingWindow.OpenToolTip")); //$NON-NLS-1$
	}
}