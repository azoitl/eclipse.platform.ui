/*******************************************************************************
 * Copyright (c) 2003 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials 
 * are made available under the terms of the Common Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/cpl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.ui.internal.progress;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;

import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.events.MouseListener;
import org.eclipse.swt.events.MouseTrackListener;
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;

import org.eclipse.ui.progress.UIJob;
import org.eclipse.ui.progress.WorkbenchJob;

import org.eclipse.ui.internal.WorkbenchWindow;
/**
 * The AnimationItem is the class that manages the animation for the progress.
 */
public abstract class AnimationItem {
	WorkbenchWindow window;
	private ProgressFloatingWindow floatingWindow;
	
	interface IAnimationContainer {
		/**
		 * The animation has started. 
		 */
		public abstract void animationStart();
		/**
		 * The animation has ended. 
		 */
		public abstract void animationDone();
	}
	
	//Create a containter that does nothing by default
	IAnimationContainer animationContainer = new IAnimationContainer() {
		/* (non-Javadoc)
		 * @see org.eclipse.ui.internal.progress.AnimationItem.IAnimationContainer#animationDone()
		 */
		public void animationDone() {
			//Do nothing by default
		}
		/* (non-Javadoc)
		 * @see org.eclipse.ui.internal.progress.AnimationItem.IAnimationContainer#animationStart()
		 */
		public void animationStart() {
			//Do nothing by default
		}
	};
	//An object used to preven concurrent modification issues
	private Object windowLock = new Object();
	/**
	 * Create a new instance of the receiver.
	 * 
	 * @param workbenchWindow
	 *            the window being created
	 * @param manager
	 *            the AnimationManager that will run this item.
	 */
	public AnimationItem(WorkbenchWindow workbenchWindow) {
		this.window = workbenchWindow;
	}
	/**
	 * Create the canvas that will display the image.
	 * 
	 * @param parent
	 */
	public void createControl(Composite parent) {
		
		Control animationItem = createAnimationItem(parent);
		
		animationItem.addMouseListener(new MouseListener() {
			/*
			 * (non-Javadoc)
			 * 
			 * @see org.eclipse.swt.events.MouseListener#mouseDoubleClick(org.eclipse.swt.events.MouseEvent)
			 */
			public void mouseDoubleClick(MouseEvent arg0) {
				ProgressManagerUtil.openProgressView(AnimationItem.this.window);
			}
			/*
			 * (non-Javadoc)
			 * 
			 * @see org.eclipse.swt.events.MouseListener#mouseDown(org.eclipse.swt.events.MouseEvent)
			 */
			public void mouseDown(MouseEvent arg0) {
				//Do nothing
			}
			/*
			 * (non-Javadoc)
			 * 
			 * @see org.eclipse.swt.events.MouseListener#mouseUp(org.eclipse.swt.events.MouseEvent)
			 */
			public void mouseUp(MouseEvent arg0) {
				//Do nothing
			}
		});
		animationItem.addMouseTrackListener(new MouseTrackListener() {
			/*
			 * (non-Javadoc)
			 * 
			 * @see org.eclipse.swt.events.MouseTrackListener#mouseEnter(org.eclipse.swt.events.MouseEvent)
			 */
			public void mouseEnter(MouseEvent e) {
				//Nothing here
			}
			/*
			 * (non-Javadoc)
			 * 
			 * @see org.eclipse.swt.events.MouseTrackListener#mouseExit(org.eclipse.swt.events.MouseEvent)
			 */
			public void mouseExit(MouseEvent e) {
				//Nothing here
			}
			/*
			 * (non-Javadoc)
			 * 
			 * @see org.eclipse.swt.events.MouseTrackListener#mouseHover(org.eclipse.swt.events.MouseEvent)
			 */
			public void mouseHover(MouseEvent e) {
				openFloatingWindow();
			}
		});
		
		AnimationManager.getInstance().addItem(this);
	}
	
	/**
	 * Create the animation item control.
	 * @param parent the parent Composite
	 * @return Control
	 */
	protected abstract Control createAnimationItem(Composite parent);
	
	/**
	 * Paint the image in the canvas.
	 * 
	 * @param event
	 *            The PaintEvent that generated this call.
	 * @param image
	 *            The image to display
	 * @param imageData
	 *            The array of ImageData. Required to show an animation.
	 */
	void paintImage(PaintEvent event, Image image, ImageData imageData) {
		event.gc.drawImage(image, 0, 0);
	}
	/**
	 * Get the SWT control for the receiver.
	 * 
	 * @return Control
	 */
	public abstract Control getControl();
	
	
	/**
	 * Open a floating window for the receiver.
	 * 
	 * @param event
	 */
	void openFloatingWindow() {
		synchronized (windowLock) {
			//Do we already have one?
			if (floatingWindow != null)
				return;
			//Don't bother if there is nothing showing yet
			if (!window.getShell().isVisible())
				return;
			floatingWindow = new ProgressFloatingWindow(window, getControl());
		}
		WorkbenchJob floatingJob = new WorkbenchJob(ProgressMessages
				.getString("AnimationItem.openFloatingWindowJob")) {//$NON-NLS-1$
			/*
			 * (non-Javadoc)
			 * 
			 * @see org.eclipse.ui.progress.UIJob#runInUIThread(org.eclipse.core.runtime.IProgressMonitor)
			 */
			public IStatus runInUIThread(IProgressMonitor monitor) {
				synchronized (windowLock) {
					//Clear the window if the parent is not visibile
					if (window.getShell() == null || !window.getShell().isVisible()
							|| getControl().isDisposed()) {
						closeAndClearFloatingWindow();
					}
					if (floatingWindow == null)
						return Status.CANCEL_STATUS;
					if (window != null
							&& window == window.getWorkbench().getActiveWorkbenchWindow()) {
						floatingWindow.open();
						for (int index = 0; index < window.getWorkbench().getWorkbenchWindowCount(); index++) {
							WorkbenchWindow progressToClose = (WorkbenchWindow) window
									.getWorkbench().getWorkbenchWindows()[index];
							if (progressToClose != window)
								progressToClose.closeFloatingWindow();
						}
					}
					return Status.OK_STATUS;
				}
			}
			/*
			 * (non-Javadoc)
			 * 
			 * @see org.eclipse.ui.progress.WorkbenchJob#shouldRun()
			 */
			public boolean shouldRun() {
				if (AnimationManager.getInstance().isAnimated())
					return true;
				synchronized (windowLock) {
					closeWindowInUI();
					return false;
				}
			}
		};
		floatingJob.setSystem(true);
		floatingJob.schedule(500);
	}
	/**
	 * The animation has begun.
	 */
	void animationStart() {
		animationContainer.animationStart();
	}
	
	/**
	 * The animation has ended.
	 */
	void animationDone() {
		closeFloatingWindow();
		animationContainer.animationDone();
	}
	/**
	 * Close the floating window.
	 */
	public void closeFloatingWindow() {
		synchronized (windowLock) {
			closeWindowInUI();
		}
	}
	/**
	 * Close the window the UI Thread.
	 */
	private void closeWindowInUI() {
		UIJob closeJob = new UIJob(ProgressMessages.getString("AnimationItem.CloseWindowJob")) { //$NON-NLS-1$
			/*
			 * (non-Javadoc)
			 * 
			 * @see org.eclipse.ui.progress.UIJob#runInUIThread(org.eclipse.core.runtime.IProgressMonitor)
			 */
			public IStatus runInUIThread(IProgressMonitor monitor) {
				closeAndClearFloatingWindow();
				return Status.OK_STATUS;
			}
		};
		closeJob.setSystem(true);
		closeJob.schedule();
	}
	/**
	 * Get the preferred width of the receiver.
	 * 
	 * @return int
	 */
	public int getPreferredWidth() {
		return AnimationManager.getInstance().getPreferredWidth() + 5;
	}
	/**
	 * Close the floating window if it exists and clear the variable.
	 */
	private void closeAndClearFloatingWindow() {
		//If there is no window than do not run
		if (floatingWindow != null)
			floatingWindow.close();
		floatingWindow = null;
	}
	/**
	 * Set the container that will be updated when this runs.
	 * @param animationContainer The animationContainer to set.
	 */
	void setAnimationContainer(IAnimationContainer container) {
		this.animationContainer = container;
	}
}
