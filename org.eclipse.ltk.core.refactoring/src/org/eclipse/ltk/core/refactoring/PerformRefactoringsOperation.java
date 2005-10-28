/*******************************************************************************
 * Copyright (c) 2005 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.ltk.core.refactoring;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.SubProgressMonitor;

import org.eclipse.core.resources.IWorkspaceRunnable;
import org.eclipse.core.resources.ResourcesPlugin;

import org.eclipse.ltk.core.refactoring.history.IRefactoringExecutionListener;
import org.eclipse.ltk.core.refactoring.history.IRefactoringHistoryService;
import org.eclipse.ltk.core.refactoring.history.RefactoringHistory;

import org.eclipse.ltk.core.refactoring.participants.RefactoringArguments;

import org.eclipse.ltk.internal.core.refactoring.Assert;
import org.eclipse.ltk.internal.core.refactoring.RefactoringCoreMessages;
import org.eclipse.ltk.internal.core.refactoring.history.RefactoringComponentFactory;
import org.eclipse.ltk.internal.core.refactoring.history.RefactoringHistoryService;

/**
 * Operation that, when run, executes a series of refactoring sequentially.
 * <p>
 * The operation should be executed via the run method offered by
 * <code>IWorkspace</code> to achieve proper delta batching.
 * </p>
 * <p>
 * Note: this class is not intended to be extended outside of the refactoring
 * framework.
 * </p>
 * <p>
 * Note: This API is considered experimental and may change in the near future.
 * </p>
 * 
 * @since 3.2
 */
public class PerformRefactoringsOperation implements IWorkspaceRunnable {

	/** Refactoring execution listener */
	private class RefactoringExecutionListener implements IRefactoringExecutionListener {

		/**
		 * {@inheritDoc}
		 */
		public void aboutToPerformRefactoring(IRefactoringHistoryService service, RefactoringDescriptor descriptor) {
			// Do nothing
		}

		/**
		 * {@inheritDoc}
		 */
		public void aboutToRedoRefactoring(IRefactoringHistoryService service, RefactoringDescriptor descriptor) {
			// Do nothing
		}

		/**
		 * {@inheritDoc}
		 */
		public void aboutToUndoRefactoring(IRefactoringHistoryService service, RefactoringDescriptor descriptor) {
			// Do nothing
		}

		/**
		 * {@inheritDoc}
		 */
		public void refactoringPerformed(final IRefactoringHistoryService service, final RefactoringDescriptor descriptor) {
			Assert.isNotNull(descriptor);
			if (!descriptor.isUnknown())
				fCurrentDescriptor= descriptor;
			else
				fCurrentDescriptor= null;
		}

		/**
		 * {@inheritDoc}
		 */
		public void refactoringRedone(IRefactoringHistoryService service, RefactoringDescriptor descriptor) {
			// Do nothing
		}

		/**
		 * {@inheritDoc}
		 */
		public void refactoringUndone(IRefactoringHistoryService service, RefactoringDescriptor descriptor) {
			// Do nothing
		}
	}

	/**
	 * The descriptor of the currently executed refactoring, or
	 * <code>null</code>
	 */
	private RefactoringDescriptor fCurrentDescriptor= null;

	/** The refactoring execution listener, or <code>null</code> */
	private IRefactoringExecutionListener fExecutionListener= null;

	/** The status of the execution */
	private RefactoringStatus fExecutionStatus= new RefactoringStatus();

	/** The refactoring history */
	private final RefactoringHistory fRefactoringHistory;

	/**
	 * Creates a new perform refactorings operation.
	 * 
	 * @param history
	 *            the refactoring history
	 */
	public PerformRefactoringsOperation(final RefactoringHistory history) {
		Assert.isNotNull(history);
		fRefactoringHistory= history;
	}

	/**
	 * Returns the execution status. Guaranteed not to be <code>null</code>.
	 * 
	 * @return the status of the session
	 */
	public RefactoringStatus getExecutionStatus() {
		return fExecutionStatus;
	}

	/**
	 * {@inheritDoc}
	 */
	public void run(final IProgressMonitor monitor) throws CoreException {
		fExecutionStatus= new RefactoringStatus();
		final RefactoringDescriptorHandle[] handles= fRefactoringHistory.getDescriptors();
		monitor.beginTask(RefactoringCoreMessages.PerformRefactoringsOperation_perform_refactorings, handles.length);
		final RefactoringComponentFactory factory= RefactoringComponentFactory.getInstance();
		try {
			fExecutionListener= new RefactoringExecutionListener();
			RefactoringCore.getRefactoringHistoryService().addExecutionListener(fExecutionListener);
			for (int index= 0; index < handles.length && !fExecutionStatus.hasFatalError(); index++) {
				boolean execute= false;
				final RefactoringDescriptor descriptor= handles[index].resolveDescriptor();
				if (descriptor != null && !descriptor.isUnknown()) {
					final Refactoring refactoring= factory.createRefactoring(descriptor);
					if (refactoring instanceof IInitializableRefactoringComponent) {
						final IInitializableRefactoringComponent component= (IInitializableRefactoringComponent) refactoring;
						final RefactoringArguments arguments= factory.createArguments(descriptor);
						if (arguments != null) {
							final RefactoringStatus status= component.initialize(arguments);
							if (!status.hasFatalError())
								execute= true;
							else {
								fExecutionStatus.merge(status);
								break;
							}
						}
					}
					if (execute) {
						final PerformRefactoringOperation operation= new PerformRefactoringOperation(refactoring, CheckConditionsOperation.ALL_CONDITIONS);
						ResourcesPlugin.getWorkspace().run(operation, new SubProgressMonitor(monitor, 1));
						if (fCurrentDescriptor != null && !fCurrentDescriptor.isUnknown())
							RefactoringHistoryService.getInstance().setDependency(fCurrentDescriptor, descriptor);
						fExecutionStatus.merge(operation.getConditionStatus());
						if (!fExecutionStatus.hasFatalError())
							fExecutionStatus.merge(operation.getValidationStatus());
					}
				}
			}
		} finally {
			fCurrentDescriptor= null;
			if (fExecutionListener != null) {
				RefactoringCore.getRefactoringHistoryService().removeExecutionListener(fExecutionListener);
				fExecutionListener= null;
			}
			monitor.done();
		}
	}
}
