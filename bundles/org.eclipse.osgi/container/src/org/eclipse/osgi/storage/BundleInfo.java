/*******************************************************************************
 * Copyright (c) 2012 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.osgi.storage;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.ProtectionDomain;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.eclipse.osgi.container.ModuleRevision;
import org.eclipse.osgi.framework.log.FrameworkLogEntry;
import org.eclipse.osgi.framework.util.Headers;
import org.eclipse.osgi.internal.container.LockSet;
import org.eclipse.osgi.internal.framework.EquinoxConfiguration;
import org.eclipse.osgi.internal.framework.EquinoxContainer;
import org.eclipse.osgi.internal.hookregistry.StorageHookFactory;
import org.eclipse.osgi.internal.hookregistry.StorageHookFactory.StorageHook;
import org.eclipse.osgi.next.internal.debug.Debug;
import org.eclipse.osgi.storage.bundlefile.BundleEntry;
import org.eclipse.osgi.storage.bundlefile.BundleFile;
import org.eclipse.osgi.storage.url.BundleResourceHandler;
import org.eclipse.osgi.storage.url.bundleentry.Handler;
import org.osgi.framework.BundleException;

public final class BundleInfo {
	public static final String OSGI_BUNDLE_MANIFEST = "META-INF/MANIFEST.MF"; //$NON-NLS-1$

	public final class Generation {
		private final long generationId;
		private final Object genMonitor = new Object();
		private File content;
		private boolean isDirectory;
		private boolean hasPackageInfo;
		private BundleFile bundleFile;
		private Dictionary<String, String> headers;
		private ModuleRevision revision;
		private ManifestLocalization headerLocalization;
		private ProtectionDomain domain;
		private NativeCodeFinder nativeCodeFinder;
		private List<StorageHook<?, ?>> storageHooks;

		Generation(long generationId) {
			this.generationId = generationId;
		}

		Generation(long generationId, File content, boolean isDirectory, boolean hasPackageInfo) {
			this.generationId = generationId;
			this.content = content;
			this.isDirectory = isDirectory;
			this.hasPackageInfo = hasPackageInfo;
		}

		public BundleFile getBundleFile() {
			synchronized (genMonitor) {
				if (bundleFile == null) {
					if (getBundleId() == 0 && content == null) {
						bundleFile = new SystemBundleFile();
					} else {
						bundleFile = getStorage().createBundleFile(content, this, isDirectory, true);
					}
				}
				return bundleFile;
			}
		}

		public void close() {
			synchronized (genMonitor) {
				if (bundleFile != null) {
					try {
						bundleFile.close();
					} catch (IOException e) {
						// ignore
					}
				}
			}
		}

		public Dictionary<String, String> getHeaders() {
			synchronized (genMonitor) {
				if (headers == null) {
					BundleEntry manifest = getBundleFile().getEntry(OSGI_BUNDLE_MANIFEST);
					try {
						headers = Headers.parseManifest(manifest.getInputStream());
					} catch (Exception e) {
						if (e instanceof RuntimeException) {
							throw (RuntimeException) e;
						}
						throw new RuntimeException("Error occurred getting the bundle manifest.", e); //$NON-NLS-1$
					}
				}
				return headers;
			}
		}

		public Dictionary<String, String> getHeaders(String locale) {
			ManifestLocalization current = getManifestLocalization();
			return current.getHeaders(locale);
		}

		public ResourceBundle getResourceBundle(String locale) {
			ManifestLocalization current = getManifestLocalization();
			String defaultLocale = Locale.getDefault().toString();
			if (locale == null) {
				locale = defaultLocale;
			}
			return current.getResourceBundle(locale, defaultLocale.equals(locale));
		}

		private ManifestLocalization getManifestLocalization() {
			synchronized (genMonitor) {
				if (headerLocalization == null) {
					headerLocalization = new ManifestLocalization(this, getHeaders(), getStorage().getConfiguration().getConfiguration(EquinoxConfiguration.PROP_ROOT_LOCALE, "en")); //$NON-NLS-1$
				}
				return headerLocalization;
			}
		}

		public void clearManifestCache() {
			synchronized (genMonitor) {
				if (headerLocalization != null) {
					headerLocalization.clearCache();
				}
			}
		}

		public long getGenerationId() {
			return this.generationId;
		}

		public boolean isDirectory() {
			synchronized (this.genMonitor) {
				return this.isDirectory;
			}
		}

		public boolean hasPackageInfo() {
			synchronized (this.genMonitor) {
				return this.hasPackageInfo;
			}
		}

		public File getContent() {
			synchronized (this.genMonitor) {
				return this.content;
			}
		}

		void setContent(File content) {
			synchronized (this.genMonitor) {
				this.content = content;
				this.isDirectory = content == null ? false : content.isDirectory();
			}
		}

		void setStorageHooks(List<StorageHook<?, ?>> storageHooks, boolean install) {
			synchronized (this.genMonitor) {
				this.storageHooks = storageHooks;
				if (install) {
					this.hasPackageInfo = BundleInfo.hasPackageInfo(getBundleFile());
				}
			}
		}

		@SuppressWarnings("unchecked")
		public <S, L, H extends StorageHook<S, L>> H getStorageHook(Class<? extends StorageHookFactory<S, L, H>> factoryClass) {
			synchronized (this.genMonitor) {
				if (this.storageHooks == null)
					return null;
				for (StorageHook<?, ?> hook : storageHooks) {
					if (hook.getFactoryClass().equals(factoryClass)) {
						return (H) hook;
					}
				}
			}
			return null;
		}

		public ModuleRevision getRevision() {
			synchronized (this.genMonitor) {
				return this.revision;
			}
		}

		public void setRevision(ModuleRevision revision) {
			synchronized (this.genMonitor) {
				this.revision = revision;
			}
		}

		public ProtectionDomain getDomain() {
			if (getBundleId() == 0) {
				return null;
			}
			synchronized (this.genMonitor) {
				if (domain == null) {
					if (revision == null) {
						throw new IllegalStateException("The revision is not yet set for this generation."); //$NON-NLS-1$
					}
					domain = getStorage().getSecurityAdmin().createProtectionDomain(revision.getBundle());
				}
				return domain;
			}
		}

		/**
		 * Gets called by BundleFile during {@link BundleFile#getFile(String, boolean)}.  This method 
		 * will allocate a File object where content of the specified path may be 
		 * stored for this generation.  The returned File object may 
		 * not exist if the content has not previously been stored.
		 * @param path the path to the content to extract from the generation
		 * @return a file object where content of the specified path may be stored.
		 */
		public File getExtractFile(String path) {
			StringBuilder builder = new StringBuilder();
			builder.append(getBundleId()).append('/').append(getGenerationId());
			if (path.length() > 0 && path.charAt(0) != '/') {
				builder.append('/');
			}
			builder.append(path);
			return getStorage().getFile(builder.toString(), true);
		}

		public BundleInfo getBundleInfo() {
			return BundleInfo.this;
		}

		public void delete() {
			getBundleInfo().delete(this);
		}

		public URL getEntry(String path) {
			BundleEntry entry = getBundleFile().getEntry(path);
			if (entry == null)
				return null;
			path = BundleFile.fixTrailingSlash(path, entry);
			try {
				//use the constant string for the protocol to prevent duplication
				return Storage.secureAction.getURL(BundleResourceHandler.OSGI_ENTRY_URL_PROTOCOL, Long.toString(getBundleId()) + BundleResourceHandler.BID_FWKID_SEPARATOR + Integer.toString(getStorage().getModuleContainer().hashCode()), 0, path, new Handler(getStorage().getModuleContainer(), entry));
			} catch (MalformedURLException e) {
				return null;
			}
		}

		public String findLibrary(String libname) {
			NativeCodeFinder currentFinder;
			synchronized (this.genMonitor) {
				if (nativeCodeFinder == null) {
					nativeCodeFinder = new NativeCodeFinder(this);
				}
				currentFinder = nativeCodeFinder;
			}
			return currentFinder.findLibrary(libname);
		}
	}

	private final Storage storage;
	private final long bundleId;
	private long nextGenerationId;
	private final Object infoMonitor = new Object();
	private LockSet<Long> generationLocks;

	public BundleInfo(Storage storage, long bundleId, long nextGenerationId) {
		this.storage = storage;
		this.bundleId = bundleId;
		this.nextGenerationId = nextGenerationId;
	}

	public long getBundleId() {
		return bundleId;
	}

	Generation createGeneration() throws BundleException {
		synchronized (this.infoMonitor) {
			if (generationLocks == null) {
				generationLocks = new LockSet<Long>(false);
			}
			boolean lockedID;
			try {
				lockedID = generationLocks.tryLock(nextGenerationId, 5, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new BundleException("Failed to obtain id locks for generation.", BundleException.STATECHANGE_ERROR, e); //$NON-NLS-1$
			}
			if (!lockedID) {
				throw new BundleException("Failed to obtain id locks for generation.", BundleException.STATECHANGE_ERROR); //$NON-NLS-1$
			}
			Generation newGeneration = new Generation(nextGenerationId++);
			return newGeneration;
		}
	}

	void unlockGeneration(Generation generation) {
		synchronized (this.infoMonitor) {
			if (generationLocks == null) {
				throw new IllegalStateException("The generation id was not locked."); //$NON-NLS-1$
			}
			generationLocks.unlock(generation.getGenerationId());
		}
	}

	Generation restoreGeneration(long generationId, File content, boolean isDirectory, boolean hasPackageInfo) {
		synchronized (this.infoMonitor) {
			Generation restoredGeneration = new Generation(generationId, content, isDirectory, hasPackageInfo);
			return restoredGeneration;
		}
	}

	public Storage getStorage() {
		return storage;
	}

	void delete(Generation generation) {
		synchronized (this.infoMonitor) {
			try {
				getStorage().delete(getStorage().getFile(getBundleId() + "/" + generation.getGenerationId(), false)); //$NON-NLS-1$
			} catch (IOException e) {
				storage.getLogServices().log(EquinoxContainer.NAME, FrameworkLogEntry.WARNING, "Error deleting generation.", e); //$NON-NLS-1$
			}
		}
	}

	public long getNextGenerationId() {
		synchronized (this.infoMonitor) {
			return nextGenerationId;
		}
	}

	public File getDataFile(String path) {
		File dataRoot = getStorage().getFile(getBundleId() + "/" + Storage.BUNDLE_DATA_DIR, false); //$NON-NLS-1$
		if (!dataRoot.exists() && (storage.isReadOnly() || !dataRoot.mkdirs())) {
			if (getStorage().getConfiguration().getDebug().DEBUG_GENERAL)
				Debug.println("Unable to create bundle data directory: " + dataRoot.getAbsolutePath()); //$NON-NLS-1$
			return null;
		}
		return path == null ? dataRoot : new File(dataRoot, path);
	}

	// Used to check the bundle manifest file for any package information.
	// This is used when '.' is on the Bundle-ClassPath to prevent reading
	// the bundle manifest for package information when loading classes.
	static boolean hasPackageInfo(BundleFile bundleFile) {
		if (bundleFile == null) {
			return false;
		}
		BundleEntry manifest = bundleFile.getEntry(OSGI_BUNDLE_MANIFEST);
		if (manifest == null) {
			return false;
		}
		BufferedReader br = null;
		try {
			br = new BufferedReader(new InputStreamReader(manifest.getInputStream()));
			String line;
			while ((line = br.readLine()) != null) {
				if (line.length() < 20)
					continue;
				switch (line.charAt(0)) {
					case 'S' :
						if (line.charAt(1) == 'p')
							if (line.startsWith("Specification-Title: ") || line.startsWith("Specification-Version: ") || line.startsWith("Specification-Vendor: ")) //$NON-NLS-1$ //$NON-NLS-2$//$NON-NLS-3$
								return true;
						break;
					case 'I' :
						if (line.startsWith("Implementation-Title: ") || line.startsWith("Implementation-Version: ") || line.startsWith("Implementation-Vendor: ")) //$NON-NLS-1$ //$NON-NLS-2$//$NON-NLS-3$ 
							return true;
						break;
				}
			}
		} catch (IOException ioe) {
			// do nothing
		} finally {
			if (br != null)
				try {
					br.close();
				} catch (IOException e) {
					// do nothing
				}
		}
		return false;
	}

}
