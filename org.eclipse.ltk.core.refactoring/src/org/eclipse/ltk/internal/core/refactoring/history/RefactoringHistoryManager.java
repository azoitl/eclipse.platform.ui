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
package org.eclipse.ltk.internal.core.refactoring.history;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.TransformerFactoryConfigurationError;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;

import org.eclipse.core.filesystem.EFS;
import org.eclipse.core.filesystem.IFileInfo;
import org.eclipse.core.filesystem.IFileStore;

import org.eclipse.ltk.core.refactoring.RefactoringDescriptor;
import org.eclipse.ltk.core.refactoring.RefactoringDescriptorHandle;
import org.eclipse.ltk.core.refactoring.history.RefactoringHistory;

import org.eclipse.ltk.internal.core.refactoring.Assert;
import org.eclipse.ltk.internal.core.refactoring.RefactoringCorePlugin;

import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * Manager for persistable refactoring histories.
 * 
 * @since 3.2
 */
public final class RefactoringHistoryManager {

	/** The index entry delimiter */
	private static final char DELIMITER_ENTRY= '\n';

	/** The index stamp delimiter */
	private static final char DELIMITER_STAMP= '\t';

	/** The index file name */
	private static final String NAME_INDEX_FILE= "index.dat"; //$NON-NLS-1$

	/**
	 * Reads refactoring information from the specified input stream.
	 * 
	 * @param stream
	 *            the input stream
	 * @return the refactoring descriptors, or an empty array
	 * @throws CoreException
	 *             if an error occurs
	 */
	public static RefactoringDescriptor[] readDescriptors(final InputStream stream) throws CoreException {
		Assert.isNotNull(stream);
		return new XmlRefactoringSessionReader().readSession(new InputSource(new BufferedInputStream(stream))).getRefactorings();
	}

	/** The location of the history files */
	private final URI fHistoryURI;

	/**
	 * Creates a new refactoring history manager.
	 * 
	 * @param uri
	 *            the URI of the refactoring history folder
	 */
	public RefactoringHistoryManager(final URI uri) {
		Assert.isNotNull(uri);
		fHistoryURI= uri;
	}

	/**
	 * Adds a refactoring descriptor to the managed history.
	 * 
	 * @param descriptor
	 *            the refactoring descriptor to add
	 * @throws CoreException
	 *             if an error occurs
	 */
	public final void addDescriptor(final RefactoringDescriptor descriptor) throws CoreException {
		Assert.isNotNull(descriptor);
		final long stamp= descriptor.getTimeStamp();
		if (stamp >= 0) {
			final IFileStore folder= stampToStore(stamp);
			if (folder != null) {
				final IFileStore history= folder.getChild(RefactoringHistoryService.NAME_REFACTORING_HISTORY);
				final IFileStore index= folder.getChild(NAME_INDEX_FILE);
				if (history != null && index != null) {
					if (history.fetchInfo().exists()) {
						InputStream input= null;
						try {
							input= new BufferedInputStream(history.openInputStream(EFS.NONE, null));
							if (input != null) {
								final Document document= DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new InputSource(input));
								if (input != null) {
									try {
										input.close();
										input= null;
									} catch (IOException exception) {
										// Do nothing
									}
								}
								final Object result= descriptorToNode(descriptor);
								if (result instanceof Document) {
									final NodeList list= ((Document) result).getElementsByTagName(IRefactoringSerializationConstants.ELEMENT_REFACTORING);
									Assert.isTrue(list.getLength() == 1);
									document.getDocumentElement().appendChild(document.importNode(list.item(0), true));
									writeHistoryEntry(history, document);
									writeIndexEntry(index, descriptor.getTimeStamp(), descriptor.getDescription());
								}
							}
						} catch (ParserConfigurationException exception) {
							new CoreException(new Status(IStatus.ERROR, RefactoringCorePlugin.getPluginId(), 0, exception.getLocalizedMessage(), null));
						} catch (IOException exception) {
							new CoreException(new Status(IStatus.ERROR, RefactoringCorePlugin.getPluginId(), 0, exception.getLocalizedMessage(), null));
						} catch (SAXException exception) {
							new CoreException(new Status(IStatus.ERROR, RefactoringCorePlugin.getPluginId(), 0, exception.getLocalizedMessage(), null));
						} finally {
							if (input != null) {
								try {
									input.close();
								} catch (IOException exception) {
									// Do nothing
								}
							}
						}
					} else {
						try {
							final Object result= descriptorToNode(descriptor);
							if (result instanceof Node) {
								writeHistoryEntry(history, (Node) result);
								writeIndexEntry(index, descriptor.getTimeStamp(), descriptor.getDescription());
							}
						} catch (IOException exception) {
							throw new CoreException(new Status(IStatus.ERROR, RefactoringCorePlugin.getPluginId(), 0, exception.getLocalizedMessage(), null));
						}
					}
				}
			}
		}
	}

	/**
	 * Transforms the specified descriptor into a history object.
	 * 
	 * @param descriptor
	 *            the descriptor to transform
	 * @return the transformation result
	 * @throws CoreException
	 *             if an error occurs
	 */
	private Object descriptorToNode(final RefactoringDescriptor descriptor) throws CoreException {
		Assert.isNotNull(descriptor);
		final IRefactoringSessionTransformer transformer= new XmlRefactoringSessionTransformer();
		try {
			transformer.beginSession(null);
			try {
				transformer.beginRefactoring(descriptor.getID(), descriptor.getTimeStamp(), descriptor.getProject(), descriptor.getDescription(), descriptor.getComment());
				for (final Iterator iterator= descriptor.getArguments().entrySet().iterator(); iterator.hasNext();) {
					final Map.Entry entry= (Entry) iterator.next();
					transformer.createArgument((String) entry.getKey(), (String) entry.getValue());
				}
			} finally {
				transformer.endRefactoring();
			}
		} finally {
			transformer.endSession();
		}
		return transformer.getResult();
	}

	/**
	 * Is the refactoring history empty?
	 * 
	 * @return <code>true</code> if it is empty, <code>false</code>
	 *         otherwise
	 * @throws CoreException
	 *             if an error occurs
	 */
	public final boolean isEmpty() throws CoreException {
		return EFS.getStore(fHistoryURI).childStores(0, null).length == 0;
	}

	/**
	 * Reads the specified number of refactoring descriptors from the head of
	 * the history.
	 * 
	 * @param store
	 *            the file store
	 * @param descriptors
	 *            the list of read descriptors
	 * @param count
	 *            the total number of descriptors to be read
	 * @throws CoreException
	 *             if an error occurs
	 */
	private void readDescriptors(final IFileStore store, final List descriptors, final int count) throws CoreException {
		Assert.isNotNull(descriptors);
		Assert.isTrue(count - descriptors.size() >= 0);
		if (count > 0) {
			final IFileInfo info= store.fetchInfo();
			if (store.getName().equalsIgnoreCase(RefactoringHistoryService.NAME_REFACTORING_HISTORY) && !info.isDirectory()) {
				final RefactoringDescriptor[] results= readDescriptors(store.openInputStream(EFS.NONE, null));
				Arrays.sort(results, new Comparator() {

					public final int compare(final Object first, final Object second) {
						return (int) (((RefactoringDescriptor) first).getTimeStamp() - ((RefactoringDescriptor) second).getTimeStamp());
					}
				});
				final int size= count - descriptors.size();
				for (int index= 0; index < results.length && index < size; index++)
					descriptors.add(results[index]);
			}
			final IFileStore[] stores= store.childStores(EFS.NONE, null);
			Arrays.sort(stores, new Comparator() {

				public final int compare(final Object first, final Object second) {
					return ((IFileStore) first).getName().compareTo(((IFileStore) second).getName());
				}
			});
			for (int index= 0; index < stores.length; index++)
				readDescriptors(stores[index], descriptors, count - descriptors.size());
		}
	}

	/**
	 * Reads the specified number of refactoring descriptors from the head of
	 * the history.
	 * 
	 * @param count
	 *            the number of descriptors
	 * @return The refactoring descriptors, or an empty array
	 */
	final RefactoringDescriptor[] readDescriptors(final int count) {
		Assert.isTrue(count >= 0);
		final List list= new ArrayList(count);
		try {
			readDescriptors(EFS.getStore(fHistoryURI), list, count);
		} catch (CoreException exception) {
			RefactoringCorePlugin.log(exception);
		}
		final RefactoringDescriptor[] descriptors= new RefactoringDescriptor[list.size()];
		list.toArray(descriptors);
		return descriptors;
	}

	/**
	 * Reads refactoring descriptor handles.
	 * 
	 * @param store
	 *            the file store to read
	 * @param list
	 *            the list of handles to fill in
	 * @param start
	 *            the start time stamp, inclusive
	 * @param end
	 *            the end time stamp, inclusive
	 * @throws CoreException
	 *             if an error occurs
	 */
	private void readHandles(final IFileStore store, final List list, final long start, final long end) throws CoreException {
		Assert.isNotNull(store);
		Assert.isNotNull(list);
		Assert.isNotNull(store);
		final IFileInfo info= store.fetchInfo();
		if (store.getName().equalsIgnoreCase(NAME_INDEX_FILE) && !info.isDirectory() && info.exists()) {
			InputStream stream= null;
			try {
				stream= store.openInputStream(0, null);
				final BufferedReader reader= new BufferedReader(new InputStreamReader(stream));
				while (reader.ready()) {
					final String line= reader.readLine();
					if (line != null) {
						final int index= line.indexOf(DELIMITER_STAMP);
						if (index > 0) {
							try {
								list.add(new RefactoringDescriptorHandle(line.substring(index + 1), new Long(line.substring(0, index)).longValue()));
							} catch (NumberFormatException exception) {
								// Just skip
							}
						}
					}
				}
			} catch (IOException exception) {
				throw new CoreException(new Status(IStatus.ERROR, RefactoringCorePlugin.getPluginId(), 0, exception.getLocalizedMessage(), null));
			} finally {
				if (stream != null) {
					try {
						stream.close();
					} catch (IOException exception) {
						// Do nothing
					}
				}
			}
		}
		final IFileStore[] stores= store.childStores(EFS.NONE, null);
		for (int index= 0; index < stores.length; index++)
			readHandles(stores[index], list, start, end);
	}

	/**
	 * Reads the refactoring history from disk.
	 * 
	 * @param start
	 *            the start time stamp, inclusive
	 * @param end
	 *            the end time stamp, inclusive
	 * @return the refactoring history
	 */
	final RefactoringHistory readHistory(final long start, final long end) {
		Assert.isTrue(end >= start);
		final List list= new ArrayList();
		try {
			readHandles(EFS.getStore(fHistoryURI), list, start, end);
		} catch (CoreException exception) {
			RefactoringCorePlugin.log(exception);
		}
		final RefactoringDescriptorHandle[] handles= new RefactoringDescriptorHandle[list.size()];
		list.toArray(handles);
		return new RefactoringHistoryImplementation(handles);
	}

	/**
	 * Recursively deletes entries of the refactoring history file tree.
	 * 
	 * @param store
	 *            the file store
	 * @throws CoreException
	 *             if an error occurs
	 */
	private void recursiveDelete(final IFileStore store) throws CoreException {
		Assert.isNotNull(store);
		final IFileInfo info= store.fetchInfo();
		if (info.isDirectory()) {
			if (info.getName().equalsIgnoreCase(RefactoringHistoryService.NAME_REFACTORINGS_FOLDER))
				return;
			final IFileStore[] stores= store.childStores(EFS.NONE, null);
			if (stores == null || stores.length > 1)
				return;
		}
		final IFileStore parent= store.getParent();
		store.delete(0, null);
		recursiveDelete(parent);
	}

	/**
	 * Removes a refactoring descriptor from the managed history.
	 * 
	 * @param stamp
	 *            the time stamp of the refactoring descriptor
	 * @throws CoreException
	 *             if an error occurs
	 */
	public final void removeDescriptor(long stamp) throws CoreException {
		Assert.isTrue(stamp >= 0);
		final IFileStore folder= stampToStore(stamp);
		if (folder != null) {
			final IFileStore history= folder.getChild(RefactoringHistoryService.NAME_REFACTORING_HISTORY);
			final IFileStore index= folder.getChild(NAME_INDEX_FILE);
			if (history != null && index != null && history.fetchInfo().exists() && index.fetchInfo().exists()) {
				InputStream input= null;
				try {
					input= new BufferedInputStream(history.openInputStream(EFS.NONE, null));
					if (input != null) {
						final Document document= DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new InputSource(input));
						final NodeList list= document.getElementsByTagName(IRefactoringSerializationConstants.ELEMENT_REFACTORING);
						final int length= list.getLength();
						for (int offset= 0; offset < length; offset++) {
							final Node node= list.item(offset);
							final NamedNodeMap attributes= node.getAttributes();
							if (attributes != null) {
								final Node item= attributes.getNamedItem(IRefactoringSerializationConstants.ATTRIBUTE_STAMP);
								if (item != null) {
									final String value= item.getNodeValue();
									if (String.valueOf(stamp).equals(value)) {
										node.getParentNode().removeChild(node);
										if (input != null) {
											try {
												input.close();
												input= null;
											} catch (IOException exception) {
												// Do nothing
											}
										}
										if (length == 1)
											recursiveDelete(folder);
										else {
											writeHistoryEntry(history, document);
											removeIndexEntry(index, stamp);
										}
										break;
									}
								}
							}
						}
					}
				} catch (ParserConfigurationException exception) {
					new CoreException(new Status(IStatus.ERROR, RefactoringCorePlugin.getPluginId(), 0, exception.getLocalizedMessage(), null));
				} catch (IOException exception) {
					new CoreException(new Status(IStatus.ERROR, RefactoringCorePlugin.getPluginId(), 0, exception.getLocalizedMessage(), null));
				} catch (SAXException exception) {
					new CoreException(new Status(IStatus.ERROR, RefactoringCorePlugin.getPluginId(), 0, exception.getLocalizedMessage(), null));
				} finally {
					if (input != null) {
						try {
							input.close();
						} catch (IOException exception) {
							// Do nothing
						}
					}
				}
			}
		}
	}

	/**
	 * Removes the specified index entry.
	 * 
	 * @param file
	 *            the history index file
	 * @param stamp
	 *            the time stamp
	 * @throws CoreException
	 *             if an error occurs
	 * @throws IOException
	 *             if an error occurs
	 */
	private void removeIndexEntry(final IFileStore file, final long stamp) throws CoreException, IOException {
		InputStream input= null;
		try {
			if (file.fetchInfo().exists()) {
				input= new DataInputStream(new BufferedInputStream(file.openInputStream(EFS.NONE, null)));
				if (input != null) {
					final String value= new Long(stamp).toString();
					final BufferedReader reader= new BufferedReader(new InputStreamReader(input));
					final StringBuffer buffer= new StringBuffer();
					while (reader.ready()) {
						final String line= reader.readLine();
						if (!line.startsWith(value)) {
							buffer.append(line);
							buffer.append('\n');
						}
					}
					try {
						input.close();
						input= null;
					} catch (IOException exception) {
						// Do nothing
					}
					OutputStream output= null;
					try {
						file.getParent().mkdir(EFS.NONE, null);
						output= new BufferedOutputStream(file.openOutputStream(EFS.NONE, null));
						output.write(buffer.toString().getBytes());
					} finally {
						if (output != null) {
							try {
								output.close();
							} catch (IOException exception) {
								// Do nothing
							}
						}
					}
				}
			}
		} finally {
			try {
				if (input != null)
					input.close();
			} catch (IOException exception) {
				// Do nothing
			}
		}
	}

	/**
	 * Returns the descriptor the specified handle points to.
	 * 
	 * @param handle
	 *            the refactoring descriptor handle
	 * @return the associated refactoring descriptor, or <code>null</code>
	 */
	final RefactoringDescriptor resolveDescriptor(final RefactoringDescriptorHandle handle) {
		Assert.isNotNull(handle);
		final long stamp= handle.getTimeStamp();
		if (stamp >= 0) {
			InputStream input= null;
			try {
				final IFileStore folder= stampToStore(stamp);
				if (folder != null) {
					final IFileStore file= folder.getChild(RefactoringHistoryService.NAME_REFACTORING_HISTORY);
					if (file != null && file.fetchInfo().exists()) {
						input= new BufferedInputStream(file.openInputStream(EFS.NONE, null));
						if (input != null)
							return new XmlRefactoringSessionReader().readDescriptor(new InputSource(input), stamp);
					}
				}
			} catch (CoreException exception) {
				// Do nothing
			} finally {
				try {
					if (input != null)
						input.close();
				} catch (IOException exception) {
					// Do nothing
				}
			}
		}
		return null;
	}

	/**
	 * Returns a file store representing the history part for the specified time
	 * stamp
	 * 
	 * @param stamp
	 *            the time stamp
	 * @return A file store which may not exist
	 * @throws CoreException
	 *             if an error occurs
	 */
	private IFileStore stampToStore(final long stamp) throws CoreException {
		Assert.isTrue(stamp >= 0);
		final Calendar calendar= Calendar.getInstance(Locale.US);
		calendar.setTimeInMillis(stamp);
		IFileStore store= EFS.getStore(fHistoryURI);
		store= store.getChild(String.valueOf(calendar.get(Calendar.YEAR)));
		store= store.getChild(String.valueOf(calendar.get(Calendar.MONTH) + 1));
		store= store.getChild(String.valueOf(calendar.get(Calendar.WEEK_OF_YEAR)));
		return store;
	}

	/**
	 * Writes a refactoring history entry.
	 * 
	 * @param file
	 *            the refactoring history file
	 * @param node
	 *            the DOM node
	 * @throws CoreException
	 *             if an error occurs
	 * @throws IOException
	 *             if an error occurs
	 */
	private void writeHistoryEntry(final IFileStore file, final Node node) throws CoreException, IOException {
		Assert.isNotNull(file);
		Assert.isNotNull(node);
		OutputStream output= null;
		try {
			file.getParent().mkdir(EFS.NONE, null);
			output= new BufferedOutputStream(file.openOutputStream(EFS.NONE, null));
			if (output != null) {
				final Transformer transformer= TransformerFactory.newInstance().newTransformer();
				transformer.setOutputProperty(OutputKeys.METHOD, IRefactoringSerializationConstants.OUTPUT_METHOD);
				transformer.setOutputProperty(OutputKeys.ENCODING, IRefactoringSerializationConstants.OUTPUT_ENCODING);
				transformer.transform(new DOMSource(node), new StreamResult(output));
			}
		} catch (TransformerConfigurationException exception) {
			throw new CoreException(new Status(IStatus.ERROR, RefactoringCorePlugin.getPluginId(), 0, exception.getLocalizedMessage(), null));
		} catch (TransformerFactoryConfigurationError exception) {
			throw new CoreException(new Status(IStatus.ERROR, RefactoringCorePlugin.getPluginId(), 0, exception.getLocalizedMessage(), null));
		} catch (TransformerException exception) {
			final Throwable throwable= exception.getException();
			if (throwable instanceof IOException)
				throw (IOException) throwable;
			RefactoringCorePlugin.log(exception);
		} finally {
			if (output != null) {
				try {
					output.close();
				} catch (IOException exception) {
					// Do nothing
				}
			}
		}
	}

	/**
	 * Writes a refactoring history index.
	 * 
	 * @param file
	 *            the history index file
	 * @param stamp
	 *            the time stamp
	 * @param description
	 *            the description of the refactoring
	 * @throws CoreException
	 *             if an error occurs
	 * @throws IOException
	 *             if an error occurs
	 */
	private void writeIndexEntry(final IFileStore file, final long stamp, final String description) throws CoreException, IOException {
		Assert.isNotNull(file);
		Assert.isNotNull(description);
		OutputStream output= null;
		try {
			file.getParent().mkdir(EFS.NONE, null);
			output= new BufferedOutputStream(file.openOutputStream(EFS.APPEND, null));
			final StringBuffer buffer= new StringBuffer(64);
			buffer.append(stamp);
			buffer.append(DELIMITER_STAMP);
			buffer.append(description);
			buffer.append(DELIMITER_ENTRY);
			output.write(buffer.toString().getBytes());
		} finally {
			if (output != null) {
				try {
					output.close();
				} catch (IOException exception) {
					// Do nothing
				}
			}
		}
	}
}