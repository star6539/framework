/*******************************************************************************
 * Copyright (c) 2008, 2010 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/

package org.eclipse.osgi.storage.bundlefile;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.Enumeration;
import org.eclipse.osgi.container.Module;
import org.eclipse.osgi.internal.hookregistry.BundleFileWrapperFactoryHook;

/**
 * Used to chain the BundleFile objects returned from {@link BundleFileWrapperFactoryHook}.  
 * This class is useful for traversing the chain of wrapped bundle files.
 */
public class BundleFileWrapperChain extends BundleFile {
	private final BundleFile wrapped;
	private final BundleFileWrapperChain next;

	public BundleFileWrapperChain(BundleFile wrapped, BundleFileWrapperChain next) {
		super(null);
		this.wrapped = wrapped;
		this.next = next;
	}

	public void close() throws IOException {
		wrapped.close();
	}

	public boolean containsDir(String dir) {
		return wrapped.containsDir(dir);
	}

	public BundleEntry getEntry(String path) {
		return wrapped.getEntry(path);
	}

	public Enumeration<String> getEntryPaths(String path) {
		return wrapped.getEntryPaths(path);
	}

	public File getFile(String path, boolean nativeCode) {
		return wrapped.getFile(path, nativeCode);
	}

	public void open() throws IOException {
		wrapped.open();
	}

	public File getBaseFile() {
		return wrapped.getBaseFile();
	}

	public URL getResourceURL(String path, Module hostModule, int index) {
		return wrapped.getResourceURL(path, hostModule, index);
	}

	public String toString() {
		return wrapped.toString();
	}

	/**
	 * The BundleFile that is wrapped
	 * @return the BunldeFile that is wrapped
	 */
	public BundleFile getWrapped() {
		return wrapped;
	}

	/**
	 * The next WrapperBundleFile in the chain.  A <code>null</code> value
	 * is returned if this is the end of the chain.
	 * @return the next WrapperBundleFile
	 */
	public BundleFileWrapperChain getNext() {
		return next;
	}
}
