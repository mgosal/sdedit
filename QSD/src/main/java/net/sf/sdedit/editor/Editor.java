// Copyright (c) 2006 - 2016, Markus Strauch.
// All rights reserved.
// 
// Redistribution and use in source and binary forms, with or without
// modification, are permitted provided that the following conditions are met:
// 
// * Redistributions of source code must retain the above copyright notice, 
// this list of conditions and the following disclaimer.
// * Redistributions in binary form must reproduce the above copyright notice, 
// this list of conditions and the following disclaimer in the documentation 
// and/or other materials provided with the distribution.
// 
// THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" 
// AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE 
// IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE 
// ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE 
// LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR 
// CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF 
// SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS 
// INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN 
// CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) 
// ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF 
// THE POSSIBILITY OF SUCH DAMAGE.

package net.sf.sdedit.editor;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import javax.swing.Action;

import net.sf.sdedit.Constants;
import net.sf.sdedit.config.Configuration;
import net.sf.sdedit.config.ConfigurationManager;
import net.sf.sdedit.config.GlobalConfiguration;
import net.sf.sdedit.editor.apple.AppInstaller;
import net.sf.sdedit.editor.plugin.FileActionProvider;
import net.sf.sdedit.editor.plugin.FileHandler;
import net.sf.sdedit.editor.plugin.Plugin;
import net.sf.sdedit.editor.plugin.PluginRegistry;
import net.sf.sdedit.server.RealtimeServer;
import net.sf.sdedit.ui.Tab;
import net.sf.sdedit.ui.UserInterface;
import net.sf.sdedit.ui.UserInterfaceListener;
import net.sf.sdedit.ui.components.buttons.ActionManager;
import net.sf.sdedit.ui.components.configuration.Bean;
import net.sf.sdedit.ui.components.configuration.ConfigurationAction;
import net.sf.sdedit.ui.impl.LookAndFeelManager;
import net.sf.sdedit.ui.impl.UserInterfaceImpl;
import net.sf.sdedit.util.OS;
import net.sf.sdedit.util.Ref;
import net.sf.sdedit.util.UIUtilities;
import net.sf.sdedit.util.Utilities;

/**
 * The control class of the Quick Sequence Diagram Editor.
 * 
 * @author Markus Strauch
 */
public final class Editor implements Constants, UserInterfaceListener

{
	private GlobalConfiguration globalConfiguration;

	private UserInterface ui;

	private Actions actions;

	// Reference to the real-time-server, if one is running, otherwise null
	private RealtimeServer server;

	private LinkedList<String> recentFiles;

	private LinkedList<Action> recentFileActions;

	// Flag denoting if the application has already been set up.
	private boolean setup = false;

	private Map<String, Object> globals;

	private ActionManager actionManager;

	private FileActionProvider fileActionProvider;

	private List<FileHandler> fileHandlers;

	private DiagramFileHandler defaultFileHandler;

	private static Editor instance;

	public static Editor getEditor() {
		if (instance == null) {
			instance = new Editor();
		}
		return instance;
	}

	private Editor() {
		UIUtilities.setGlobalFont(ConfigurationManager.getGlobalConfiguration().getGuiFont());
		String laf = ConfigurationManager.getGlobalConfiguration().getLookAndFeel();
		LookAndFeelManager.changeTo(laf);
		globals = new HashMap<String, Object>();

		fileHandlers = new LinkedList<FileHandler>();

		defaultFileHandler = new DiagramFileHandler();

		// we do not use addFileHandler
		fileHandlers.add(defaultFileHandler);

		actionManager = new ActionManager();

		ui = newUI();

		if (OS.TYPE == OS.Type.MAC) {
			AppInstaller.installApplication(this);
		}
		recentFiles = new LinkedList<String>();
		recentFileActions = new LinkedList<Action>();
		globalConfiguration = ConfigurationManager.getGlobalConfiguration();

		fileActionProvider = new FileActionProvider();

		ui.addListener(this);

	}

	private void readLastWrokingDir()
	{
		String lastWrkDir = globalConfiguration.getLastWorkDir();
	}
	public void start() {
		setupUI();
		for (Plugin plugin : PluginRegistry.getInstance()) {
			for (FileHandler handler : plugin.getFileHandlers()) {
				addFileHandler(handler);
			}
		}
		readLastWrokingDir();
		readRecentFiles();
		if (globalConfiguration.isAutostartServer()) {
			try {
				startRealtimeServer(globalConfiguration.getRealtimeServerPort());
				ui.message("Started real-time diagram server @localhost:" + server.getPort());
			} catch (Exception e) {
				ui.errorMessage(e, null, "The real-time diagram server could not be started.");
			}
		}
		setup = true;
		if (OS.TYPE == OS.Type.MAC) {
			File fileToLoad = AppInstaller.getFileToLoad();
			if (fileToLoad != null) {
				try {
					load(fileToLoad.toURI().toURL());
				} catch (RuntimeException e) {
					throw e;
				} catch (Exception e) {
					ui.errorMessage(e, null, "Cannot load " + fileToLoad.getAbsolutePath());
				}
			}
		}
	}

	private void addFileHandler(FileHandler fileHandler) {
		fileHandlers.add(fileHandler);
	}

	public ActionManager getActionManager() {
		return actionManager;
	}

	public void registerGlobalObject(String name, Object object) {
		globals.put(name, object);
	}

	public Object getGlobalObject(String name) {
		return globals.get(name);
	}

	public Iterable<FileHandler> getFileHandlers() {
		final List<FileHandler> handlers = new ArrayList<FileHandler>();
		handlers.add(defaultFileHandler);
		for (Plugin plugin : PluginRegistry.getInstance()) {
			for (FileHandler handler : plugin.getFileHandlers()) {
				handlers.add(handler);
			}
		}
		return new Iterable<FileHandler>() {
			public Iterator<FileHandler> iterator() {
				return handlers.iterator();
			}
		};
	}
	private FileHandler findFileHandlerNoExtension(String type) {
		for (FileHandler fileHandler : fileHandlers) {
			if(fileHandler.canLoad()){
				return fileHandler;
			}
		}
		return null;
	}
	private FileHandler findFileHandler(String type) {
		for (FileHandler fileHandler : fileHandlers) {
			for (String ext : fileHandler.getFileTypes()) {
				if (ext.equalsIgnoreCase(type)) {
					return fileHandler;
				}
			}
		}
		return null;
	}

	private List<String> getFileTypes() {
		List<String> fileTypes = new ArrayList<String>();
		for (FileHandler fileHandler : fileHandlers) {
			for (String ext : fileHandler.getFileTypes()) {
				fileTypes.add(ext);
			}
		}
		return fileTypes;
	}

	public Tab load(URL url) throws IOException, URISyntaxException {
		String file = url.getFile();
		FileHandler handler = null;
		int d = file.lastIndexOf('.');
		if (d >= 0) {
			String ext = file.substring(d + 1);
			handler = findFileHandler(ext);
		}
		else if(d==-1)
		{
			handler = findFileHandlerNoExtension(file);
		}
		if (handler == null) {
			ui.errorMessage(null, null,
					"Cannot handle " + file + "\nSupported file-types are: " + Utilities.join(",", getFileTypes()));
			return null;
		}
		return handler.loadFile(url, ui);

	}

	/**
	 * @see net.sf.sdedit.ui.UserInterfaceListener#hyperlinkClicked(java.lang.String)
	 */
	public void hyperlinkClicked(String hyperlink) {
		if (hyperlink.startsWith("example:")) {
			String file = hyperlink.substring(hyperlink.indexOf(':') + 1);
			actions.getExampleAction(file, file).actionPerformed(null);
		} else if (hyperlink.startsWith("help:")) {
			int first = hyperlink.indexOf(':');
			int last = hyperlink.lastIndexOf(':');
			String title = hyperlink.substring(first + 1, last);
			String file = hyperlink.substring(last + 1);
			ui.help(title, file.replaceAll(".html", ""), false);
		}
	}

	private void readRecentFiles() {
		String sep = System.getProperty("path.separator");
		String recent = globalConfiguration.getRecentFiles();
		if (recent != null && !recent.equals("")) {
			int i = 0;
			for (String file : recent.split(sep)) {
				if (new File(file).exists()) {
					i++;
					recentFiles.add(file);
					Action act = actions.getRecentFileAction(file);
					recentFileActions.add(act);
					ui.addAction("&File.Open &recent file", act, null);
					if (i == globalConfiguration.getMaxNumOfRecentFiles()) {
						return;
					}
				}
			}
		}
	}

	public List<String> getRecentFiles() {
		return Collections.checkedList(recentFiles, String.class);
	}

	public void addToRecentFiles(String file) {
		int max = globalConfiguration.getMaxNumOfRecentFiles();
		if (max == 0) {
			return;
		}
		int i = recentFiles.indexOf(file);
		Action act;
		if (i >= 0) {
			recentFiles.remove(i);
			act = recentFileActions.get(i);
			recentFileActions.remove(i);

		} else {
			act = actions.getRecentFileAction(file);
			ui.addAction("&File.Open &recent file", act, null);
			if (recentFiles.size() == max) {
				Action last = recentFileActions.removeLast();
				ui.removeAction("&File.Open &recent file", last);
				recentFiles.removeLast();
			}
		}
		recentFiles.addFirst(file);
		recentFileActions.addFirst(act);
	}

	private void writeRecentFiles() {
		String sep = System.getProperty("path.separator");
		StringBuffer buffer = new StringBuffer();
		for (String file : recentFiles) {
			if (buffer.length() > 0) {
				buffer.append(sep);
			}
			buffer.append(file);
		}
		globalConfiguration.setRecentFiles(buffer.toString());
	}

	public int startRealtimeServer(int port) throws IOException {
		if (isServerRunning()) {
			return 0;
		}
		server = new RealtimeServer(port, this);
		server.setDaemon(true);
		server.start();
		return server.getPort();
	}

	public boolean isServerRunning() {
		return server != null;
	}

	public void shutDownServer() {
		if (isServerRunning()) {
			server.shutDown();
			server = null;
		}
	}

	private void setupUI() {

		addActions();

		ui.showUI();
		ui.addToolbarSeparator();
		ui.addToToolbar(actions.helpAction, null);
	}

	@SuppressWarnings("serial")
	private void addActions() {

		actions = new Actions(this);

		ui.addAction("&File", actions.newSequenceDiagramAction, null);

		for (Plugin plugin : PluginRegistry.getInstance()) {
			if (plugin.getNewTabAction() != null) {
				ui.addAction("&File", plugin.getNewTabAction(), null);
			}
		}

		// ui.addCategory("&File.Open", "open");

		ui.addAction("&File", fileActionProvider.getOpenAction(ui), fileActionProvider.getOpenActivator);

		ui.addCategory("&File.Open &recent file", "open");

		ui.addAction("&File", fileActionProvider.getSaveAction(defaultFileHandler, ui),
				fileActionProvider.getSaveActivator);

		ui.addAction("&File", fileActionProvider.getSaveAsAction(defaultFileHandler, ui),
				fileActionProvider.getSaveActivator);

		Action exportAction = actions.getExportAction();

		if (exportAction != null) {
			ui.addAction("&File", exportAction, actions.nonEmptyDiagramActivator);
		}

		ui.addAction("&File", actions.closeTabAction, actions.canCloseActivator);
		ui.addAction("&File", actions.closeAllAction, null);

		Action printPDFAction = actions.getPrintAction("pdf");
		if (printPDFAction != null) {
			ui.addAction("&File", printPDFAction, actions.noDiagramErrorActivator);
		}
		ui.addAction("&File", actions.quitAction, null);

		ConfigurationAction<Configuration> wrapAction = new TabConfigurationAction("lineWrap",
				"[control shift W]&Wrap lines", "Wrap lines whose length exceed the width of the text area", "wrap",
				ui);

		ConfigurationAction<Configuration> threadedAction = new TabConfigurationAction("threaded",
				Shortcuts.getShortcut(Shortcuts.ENABLE_THREADS) + "Enable &multithreading",
				"Create diagrams with arbitrarily many sequences running concurrently", "threads", ui);

		ConfigurationAction<GlobalConfiguration> autoUpdateAction = new ConfigurationAction<GlobalConfiguration>(
				"autoUpdate", "Auto-redraw", "Update diagram as you type", "reload") {
			@Override
			public Bean<GlobalConfiguration> getBean() {
				return ConfigurationManager.getGlobalConfigurationBean();
			}
		};

		ConfigurationAction<GlobalConfiguration> autoScrollAction = new ConfigurationAction<GlobalConfiguration>(
				"autoScroll", "Auto-scrolling",
				"Scroll automatically to where the message currently being specified is visible", "autoscroll") {
			@Override
			public Bean<GlobalConfiguration> getBean() {
				return ConfigurationManager.getGlobalConfigurationBean();
			}
		};

		ui.addAction("&Edit", actions.undoAction, actions.textTabActivator);
		ui.addAction("&Edit", actions.redoAction, actions.textTabActivator);
		ui.addAction("&Edit", actions.clearAction, actions.textTabActivator);

		ui.addConfigurationAction("&Edit", threadedAction, actions.textTabActivator);

		ui.addAction("&Edit", actions.configureGloballyAction, null);
		ui.addAction("&Edit", actions.configureDiagramAction, actions.diagramTabActivator);
		ui.addAction("&Edit", actions.copyBitmapToClipBoardAction, actions.nonEmptyDiagramActivator);
		ui.addAction("&Edit", actions.copyVectorGraphicsToClipBoardAction, actions.nonEmptyDiagramActivator);
		/*
		 * ui.addAction("&Edit", actions.prettyPrintAction,
		 * actions.nonEmptyDiagramActivator);
		 */

		ui.addCategory("&View", null);

		ui.addConfigurationAction("&View", autoUpdateAction, null);
		ui.addConfigurationAction("&View", autoScrollAction, null);

		ui.addAction("&View", actions.redrawAction, actions.diagramTabActivator);

		ui.addAction("&View", actions.widenAction, actions.canConfigureActivator);
		ui.addAction("&View", actions.narrowAction, actions.canNarrowActivator);
		ui.addConfigurationAction("&View", wrapAction, actions.textTabActivator);
		ui.addAction("&View", actions.fullScreenAction, actions.supportsFullScreenActivator);

		ui.addAction("&View", actions.splitLeftRightAction, actions.horizontalSplitPossibleActivator);
		ui.addAction("&View", actions.splitTopBottomAction, actions.verticalSplitPossibleActivator);

		if (OS.TYPE != OS.Type.MAC) {
			ui.setQuitAction(actions.quitAction);
		}

		ui.addToToolbar(actions.newSequenceDiagramAction, null);

		for (Plugin plugin : PluginRegistry.getInstance()) {
			if (plugin.getNewTabAction() != null) {
				ui.addToToolbar(plugin.getNewTabAction(), null);
			}
		}

		ui.addToToolbar(fileActionProvider.getOpenAction(ui), fileActionProvider.getOpenActivator);
		ui.addToToolbar(fileActionProvider.getSaveAction(defaultFileHandler, ui), fileActionProvider.getSaveActivator);
		ui.addToToolbar(fileActionProvider.getSaveAsAction(defaultFileHandler, ui),
				fileActionProvider.getSaveActivator);

		ui.addToToolbar(exportAction, actions.nonEmptyDiagramActivator);

		if (printPDFAction != null) {
			ui.addToToolbar(printPDFAction, actions.noDiagramErrorActivator);
		}

		ui.addToolbarSeparator();

		ui.addToToolbar(actions.configureGloballyAction, null);
		ui.addToToolbar(actions.configureDiagramAction, actions.diagramTabActivator);
		ui.addToToolbar(actions.redrawAction, actions.diagramTabActivator);

		ui.addToolbarSeparator();

		ui.addToToolbar(actions.fullScreenAction, actions.supportsFullScreenActivator);
		ui.addToToolbar(actions.splitLeftRightAction, actions.horizontalSplitPossibleActivator);
		ui.addToToolbar(actions.splitTopBottomAction, actions.verticalSplitPossibleActivator);

		ui.addToolbarSeparator();

		ui.addToToolbar(actions.homeAction, actions.homeActivator);
		ui.addToToolbar(actions.previousAction, actions.previousActivator);
		ui.addToToolbar(actions.nextAction, actions.nextActivator);

		ui.addAction("E&xtras", actions.serverAction, null);
		ui.addAction("E&xtras", actions.filterAction, actions.textTabActivator);
		ui.addAction("E&xtras", new ExportMapAction(this), actions.exportMapFileActivator);

		ui.addAction("&Help", actions.helpAction, null);
		ui.addAction("&Help", actions.tutorialAction, null);
		ui.addAction("&Help", actions.helpOnMultithreadingAction, null);
		ui.addAction("&Help", actions.asyncNotesAction, null);
		if (OS.TYPE != OS.Type.MAC) {
			ui.addAction("&Help", actions.showAboutDialogAction, null);
		}

		ui.addAction("&Help.&Examples", actions.getExampleAction("Ticket order", "order.sdx"), null);
		ui.addAction("&Help.&Examples", actions.getExampleAction("Breadth first search", "bfs.sdx"), null);
		ui.addAction("&Help.&Examples", actions.getExampleAction("Levels", "levels.sdx"), null);
		ui.addAction("&Help.&Examples", actions.getExampleAction("SSH 2 (by courtesy of Carlos Duarte)", "ssh.sdx"),
				null);
		ui.addAction("&Help.&Examples", actions.getExampleAction("Webserver", "webserver.sdx"), null);

	}

	public DiagramFileHandler getDefaultFileHandler() {
		return defaultFileHandler;
	}

	public boolean isSetup() {
		return setup;
	}

	public void quit() {
		if (closeAll()) {
			writeRecentFiles();
			try {
				ConfigurationManager.storeConfigurations();
			} catch (IOException e) {
				e.printStackTrace();
				ui.errorMessage(e, null, "Could not save the global preferences file.");
			}
			if (server != null) {
				server.shutDown();
			}
			ui.exit();
			System.exit(0);
			/*
			 * if (Eclipse.getEclipse() == null) {
			 * 
			 * }
			 */
		}
	}

	/**
	 * Returns true if ALL tabs could be closed.
	 */
	boolean closeAll() {
		boolean confirmed = true;
		Ref<Boolean> noToAll = new Ref<Boolean>(false);
		for (Tab tab : ui.getTabContainer().getTabs()) {
			ui.selectTab(tab);
			if (!tab.isReadyToBeClosed(noToAll)) {
				confirmed = false;
				break;
			}
			if (noToAll.t) {
				confirmed = true;
				break;
			}
		}
		if (confirmed) {
			for (Tab tab : ui.getTabContainer().getTabs()) {
				tab.close(false);
			}
			return true;
		}
		return false;
	}

	/**
	 * Returns the user interface.
	 * 
	 * @return the user interface
	 */
	public UserInterface getUI() {
		return ui;
	}

	private UserInterface newUI() {
		return new UserInterfaceImpl();
	}

	public void tabChanged(Tab previousTab, Tab currentTab) {

		if (previousTab != null) {
			previousTab.deactivate(actionManager, fileActionProvider);
		}

		if (currentTab != null) {
			currentTab.activate(actionManager, fileActionProvider);
		}
	}
}
