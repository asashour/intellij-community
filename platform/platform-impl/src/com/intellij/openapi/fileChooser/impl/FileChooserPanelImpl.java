// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.fileChooser.impl;

import com.intellij.execution.process.ProcessIOExecutorService;
import com.intellij.execution.wsl.WSLDistribution;
import com.intellij.execution.wsl.WSLUtil;
import com.intellij.execution.wsl.WslDistributionManager;
import com.intellij.icons.AllIcons;
import com.intellij.ide.IdeBundle;
import com.intellij.ide.highlighter.ArchiveFileType;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.Experiments;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileChooserPanel;
import com.intellij.openapi.fileTypes.FileTypeRegistry;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.util.NlsSafe;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.io.NioFiles;
import com.intellij.openapi.util.text.Strings;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.local.CoreLocalFileSystem;
import com.intellij.openapi.vfs.local.CoreLocalVirtualFile;
import com.intellij.ui.ListSpeedSearch;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.SortedListModel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBPanel;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.ui.JBInsets;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.StatusText;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static com.intellij.openapi.fileChooser.ex.FileChooserDialogImpl.FILE_CHOOSER_SHOW_PATH_PROPERTY;
import static com.intellij.openapi.util.Pair.pair;
import static java.awt.GridBagConstraints.*;
import static java.util.Objects.requireNonNull;

final class FileChooserPanelImpl extends JBPanel<FileChooserPanelImpl> implements FileChooserPanel, Disposable {
  private static final Logger LOG = Logger.getInstance(FileChooserPanelImpl.class);

  private final FileTypeRegistry myRegistry;
  private final FileChooserDescriptor myDescriptor;
  private final Runnable myCallback;
  private final @Nullable WatchService myWatcher;
  private final Map<Path, FileSystem> myOpenFileSystems;

  private final ComboBox<Path> myPath;
  private final SortedListModel<FsItem> myModel;
  private final JBList<FsItem> myList;
  private boolean myShowPathBar;
  private volatile boolean myShowHiddenFiles;

  private final Object myLock = new String("file.chooser.panel.lock");
  private int myCounter = 0;
  private Pair<Integer, Future<?>> myCurrentTask = pair(-1, CompletableFuture.completedFuture(null));
  // guarded by `myLock` via `update()` callback, which the inspection doesn't detect
  @SuppressWarnings("FieldAccessedSynchronizedAndUnsynchronized") private @Nullable Path myCurrentDirectory;
  @SuppressWarnings("FieldAccessedSynchronizedAndUnsynchronized") private final List<FsItem> myCurrentContent = new ArrayList<>();
  @SuppressWarnings("FieldAccessedSynchronizedAndUnsynchronized") private @Nullable WatchKey myWatchKey;

  FileChooserPanelImpl(@NotNull FileChooserDescriptor descriptor, @NotNull Runnable callback, Path @NotNull [] recentPaths) {
    super(new GridBagLayout());

    myRegistry = FileTypeRegistry.getInstance();
    myDescriptor = descriptor;
    myCallback = callback;
    myWatcher = startWatchService();
    myOpenFileSystems = new ConcurrentHashMap<>();

    myShowHiddenFiles = descriptor.isShowHiddenFiles();
    myShowPathBar = PropertiesComponent.getInstance().getBoolean(FILE_CHOOSER_SHOW_PATH_PROPERTY, true);

    var label = new JLabel(descriptor.getDescription());

    var group = (DefaultActionGroup)ActionManager.getInstance().getAction("FileChooserToolbar");
    var toolBar = ActionManager.getInstance().createActionToolbar("FileChooserDialog", group, true);
    toolBar.setTargetComponent(this);

    myPath = new ComboBox<>(recentPaths);
    myPath.setVisible(myShowPathBar);
    myPath.setEditable(true);
    Insets pathInsets = myPath.getInsets(), pathBorder = myPath.getBorder().getBorderInsets(myPath);

    myModel = new SortedListModel<>(FsItem.COMPARATOR);
    myList = new JBList<>(myModel);
    myList.setCellRenderer(new MyListCellRenderer());
    myList.setSelectionMode(descriptor.isChooseMultiple() ? ListSelectionModel.MULTIPLE_INTERVAL_SELECTION : ListSelectionModel.SINGLE_SELECTION);
    myList.addMouseListener(new MouseAdapter() {
      @Override
      public void mouseClicked(MouseEvent e) {
        if (e.getClickCount() == 2) {
          var idx = myList.locationToIndex(e.getPoint());
          if (idx >= 0) {
            openItemAtIndex(idx, e);
          }
        }
      }
    });
    myList.addKeyListener(new KeyAdapter() {
      @Override
      public void keyPressed(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.VK_ENTER && e.getModifiersEx() == 0) {
          int[] selection = myList.getSelectedIndices();
          if (selection.length == 1) {
            openItemAtIndex(selection[0], e);
          }
        }
      }
    });
    new ListSpeedSearch<>(myList, rec -> rec.name);

    var scrollPane = ScrollPaneFactory.createScrollPane(myList);
    @SuppressWarnings("UseDPIAwareInsets") var scrollInsets = new Insets(JBUI.scale(5) - pathInsets.bottom, pathInsets.left, 0, pathInsets.right);
    scrollPane.setBorder(BorderFactory.createCompoundBorder(
      BorderFactory.createLineBorder(UIUtil.getBoundsColor()),
      BorderFactory.createEmptyBorder(pathBorder.top, pathBorder.left, pathBorder.bottom, pathBorder.right)));

    add(label, new GridBagConstraints(0, 0, 1, 1, 1.0, 0.01, CENTER, HORIZONTAL, JBInsets.emptyInsets(), 0, 0));
    add(toolBar.getComponent(), new GridBagConstraints(0, 1, 1, 1, 1.0, 0.01, CENTER, HORIZONTAL, JBInsets.emptyInsets(), 0, 0));
    add(myPath, new GridBagConstraints(0, 2, 1, 1, 1.0, 0.01, CENTER, HORIZONTAL, JBInsets.emptyInsets(), 0, 0));
    add(scrollPane, new GridBagConstraints(0, 3, 1, 1, 1.0, 0.98, CENTER, BOTH, scrollInsets, 0, 0));
  }

  private @Nullable WatchService startWatchService() {
    try {
      var watcher = FileSystems.getDefault().newWatchService();
      ProcessIOExecutorService.INSTANCE.execute(() -> {
        while (true) {
          try {
            var key = watcher.take();
            var events = key.pollEvents();
            key.reset();
            if (!events.isEmpty()) {
              UIUtil.invokeLaterIfNeeded(() -> {
                synchronized (myLock) {
                  if (key == myWatchKey && myCurrentDirectory != null) {
                    doLoad(myCurrentDirectory);
                  }
                }
              });
            }
          }
          catch (InterruptedException ignored) { }
          catch (ClosedWatchServiceException e) {
            break;
          }
        }
      });
      return watcher;
    }
    catch (Exception e) {
      LOG.warn(e);
      return null;
    }
  }

  private void openItemAtIndex(int idx, InputEvent e) {
    FsItem item = myModel.get(idx);
    if (item.directory) {
      doLoad(item.path);
    }
    else if (myDescriptor.isChooseJarContents() && myRegistry.getFileTypeByFileName(item.name) == ArchiveFileType.INSTANCE) {
      doLoad(item.path, true);
    }
    else {
      myCallback.run();
    }
    e.consume();
  }

  JComponent getPreferredFocusedComponent() {
    return myShowPathBar ? myPath : myList;
  }

  @NotNull List<@NotNull Path> chosenPaths() {
    if (myShowPathBar && myPath.isAncestorOf(KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner())) {
      var item = myPath.getEditor().getItem();
      var path = item instanceof Path ? (Path)item :
                 item instanceof String && !((String)item).isBlank() ? NioFiles.toPath(((String)item).trim()) :
                 null;
      return path != null ? List.of(path) : List.of();
    }
    else {
      return myList.getSelectedValuesList().stream()
        .filter(r -> r.selectable)
        .map(r -> requireNonNull(r.path))
        .collect(Collectors.toList());
    }
  }

  @Override
  public void load(@Nullable Path path) {
    if (path == null || path.isAbsolute()) {
      doLoad(path);
    }
    else {
      throw new IllegalArgumentException("Not absolute: " + path);
    }
  }

  @Override
  public void reload() {
    synchronized (myLock) {
      doLoad(myCurrentDirectory);
    }
  }

  @Override
  public boolean showPathBar() {
    return myShowPathBar;
  }

  @Override
  public void showPathBar(boolean show) {
    myShowPathBar = show;
    PropertiesComponent.getInstance().setValue(FILE_CHOOSER_SHOW_PATH_PROPERTY, Boolean.toString(show));
    myPath.setVisible(show);
    (show ? myPath : myList).requestFocusInWindow();
  }

  @Override
  public boolean showHiddenFiles() {
    return myShowHiddenFiles;
  }

  @Override
  public void showHiddenFiles(boolean show) {
    myShowHiddenFiles = show;
    synchronized (myLock) {
      if (myCurrentDirectory != null) {
        myModel.clear();
        for (FsItem item : myCurrentContent) {
          if (show || item.visible) myModel.add(item);
        }
      }
    }
  }

  @Override
  public void dispose() {
    synchronized (myLock) {
      cancelCurrentTask();
    }
    if (myWatcher != null) {
      try {
        myWatcher.close();
      }
      catch (IOException e) {
        LOG.warn(e);
      }
    }
    myOpenFileSystems.forEach((p, fs) -> {
      try {
        fs.close();
      }
      catch (IOException e) {
        LOG.warn(e);
      }
    });
  }

  private void doLoad(@Nullable Path path) {
    doLoad(path, false);
  }

  private void doLoad(@Nullable Path path, boolean asZip) {
    synchronized (myLock) {
      myPath.setItem(path);
      myModel.clear();
      myList.clearSelection();
      myList.setPaintBusy(true);
      myList.setEmptyText(StatusText.getDefaultEmptyText());

      myCurrentDirectory = null;
      myCurrentContent.clear();
      cancelCurrentTask();
      var id = myCounter++;
      if (LOG.isTraceEnabled()) LOG.trace("starting: " + id + ", " + path);
      myCurrentTask = pair(id, ProcessIOExecutorService.INSTANCE.submit(() -> {
        var directory = directoryToLoad(path, asZip);
        if (directory != null) {
          loadDirectory(directory, id);
        }
        else {
          loadRoots(id);
        }
      }));
    }
  }

  private void cancelCurrentTask() {
    myCurrentTask.second.cancel(true);
    if (myWatchKey != null) {
      myWatchKey.cancel();
      myWatchKey = null;
    }
  }

  private @Nullable Path directoryToLoad(@Nullable Path path, boolean asZip) {
    if (path != null && asZip) {
      try {
        @SuppressWarnings("resource") var fs = myOpenFileSystems.computeIfAbsent(path, k -> {
          try {
            return FileSystems.newFileSystem(path, null);
          }
          catch (IOException e) {
            throw new UncheckedIOException(e);
          }
        });
        return fs.getRootDirectories().iterator().next();
      }
      catch (Exception e) {
        LOG.warn(e);
        return path.getParent();
      }
    }

    for (var current = path; current != null; current = parent(current)) {
      try {
        var attributes = Files.readAttributes(current, BasicFileAttributes.class);
        return attributes.isDirectory() ? current : current.getParent();
      }
      catch (Exception e) {
        LOG.trace(e);
      }
    }

    return null;
  }

  private void loadDirectory(Path directory, int id) {
    var cancelled = new AtomicBoolean(false);

    var uplink = new FsItem(parent(directory));
    update(id, cancelled, () -> {
      myCurrentDirectory = directory;
      myPath.setItem(directory);
      myCurrentContent.add(uplink);
      myModel.add(uplink);
      myList.setSelectedIndex(0);
    });

    try {
      var vfsDirectory = new PreloadedDirectory(directory);
      var showHiddenFiles = myShowHiddenFiles;
      Files.walkFileTree(directory, EnumSet.noneOf(FileVisitOption.class), 1, new SimpleFileVisitor<>() {
        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
          var virtualFile = new LazyDirectoryOrFile(vfsDirectory, file, attrs);
          var visible = myDescriptor.isFileVisible(virtualFile, showHiddenFiles);
          var selectable = myDescriptor.isFileSelectable(virtualFile);
          var icon = myDescriptor.getIcon(virtualFile);
          var item = new FsItem(file, attrs, visible, selectable, icon);
          update(id, cancelled, () -> {
            myCurrentContent.add(item);
            if (visible) {
              myModel.add(item);
            }
          });
          return cancelled.get() ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
        }
      });
    }
    catch (Exception e) {
      LOG.warn(directory.toString(), e);
    }

    if (!cancelled.get()) {
      WatchKey watchKey = null;
      if (myWatcher != null && directory.getFileSystem() == FileSystems.getDefault()) {
        try {
          watchKey = directory.register(myWatcher, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_DELETE);
        }
        catch (Exception e) {
          if (LOG.isDebugEnabled()) LOG.debug("cannot watch " + directory, e);
        }
      }

      var _watchKey = watchKey;
      update(
        id,
        () -> {
          myList.setPaintBusy(false);
          myWatchKey = _watchKey;
        },
        () -> { if (_watchKey != null) _watchKey.cancel(); });
    }
  }

  private void loadRoots(int id) {
    var cancelled = new AtomicBoolean(false);

    update(id, cancelled, () -> {
      myCurrentDirectory = null;
      myPath.setItem(null);
    });

    var roots = new ArrayList<Path>();
    for (Path root : FileSystems.getDefault().getRootDirectories()) roots.add(root);
    if (WSLUtil.isSystemCompatible() && Experiments.getInstance().isFeatureEnabled("wsl.p9.show.roots.in.file.chooser")) {
      try {
        List<WSLDistribution> distributions = WslDistributionManager.getInstance().getInstalledDistributionsFuture().get(200, TimeUnit.MILLISECONDS);
        for (WSLDistribution distribution : distributions) roots.add(distribution.getUNCRootPath());
      }
      catch (Exception e) {
        LOG.warn(e);
      }
    }

    for (Path root : roots) {
      if (cancelled.get()) break;
      try {
        var attrs = Files.readAttributes(root, BasicFileAttributes.class);
        var virtualFile = new LazyDirectoryOrFile(null, root, attrs);
        var selectable = myDescriptor.isFileSelectable(virtualFile);
        var item = new FsItem(root, attrs, true, selectable, AllIcons.Nodes.Folder);
        update(id, cancelled, () -> myModel.add(item));
      }
      catch (Exception e) {
        LOG.warn(root.toString(), e);
      }
    }

    if (!cancelled.get()) {
      update(id, cancelled, () -> {
        myList.setPaintBusy(false);
        if (myModel.getSize() == 0) {
          myList.setEmptyText(IdeBundle.message("chooser.cannot.load.roots"));
        }
      });
    }
  }

  private static @Nullable Path parent(Path path) {
    var parent = path.getParent();
    if (parent == null) {
      var uri = path.toUri();
      if ("jar".equals(uri.getScheme())) {
        var fileUri = Strings.trimEnd(uri.getRawSchemeSpecificPart(), "!/");
        try {
          return Path.of(new URI(fileUri));
        }
        catch (Exception e) {
          LOG.warn(e);
        }
      }
    }
    return parent;
  }

  private void update(int id, AtomicBoolean cancelled, Runnable operation) {
    update(id, operation, () -> cancelled.set(true));
  }

  private void update(int id, Runnable whenActive, Runnable whenCancelled) {
    UIUtil.invokeLaterIfNeeded(() -> {
      synchronized (myLock) {
        boolean active = myCurrentTask.first == id && !myCurrentTask.second.isCancelled();
        (active ? whenActive : whenCancelled).run();
      }
    });
  }

  private static class FsItem {
    private static final String UPLINK = "..";

    private final Path path;
    private final @NlsSafe String name;
    private final boolean directory;
    private final boolean visible;
    private final boolean selectable;
    private final @Nullable Icon icon;

    private FsItem(Path path) {
      this.path = path;
      this.name = UPLINK;
      this.directory = true;
      this.visible = true;
      this.selectable = false;
      this.icon = AllIcons.Nodes.UpFolder;
    }

    private FsItem(Path path, BasicFileAttributes attrs, boolean visible, boolean selectable, @Nullable Icon icon) {
      this.path = path;
      String name = NioFiles.getFileName(path);
      this.name = name.length() > 1 && name.endsWith(File.separator) ? name.substring(0, name.length() - 1) : name;
      this.directory = attrs.isDirectory();
      this.visible = visible;
      this.selectable = selectable;
      this.icon = icon;
    }

    private static final Comparator<FsItem> COMPARATOR = (o1, o2) -> {
      if (o1.name == UPLINK) return -1;
      if (o2.name == UPLINK) return 1;
      var byType = Boolean.compare(o2.directory, o1.directory);
      return byType != 0 ? byType : o1.name.compareTo(o2.name);
    };
  }

  private static class MyListCellRenderer extends JLabel implements ListCellRenderer<FsItem> {
    @Override
    public Component getListCellRendererComponent(JList<? extends FsItem> list, FsItem value, int index, boolean selected, boolean focused) {
      setIcon(value.icon);
      setText(value.name);
      setEnabled(value.selectable);
      return this;
    }
  }

  private static class PreloadedDirectory extends CoreLocalVirtualFile {
    private static final CoreLocalFileSystem FS = new CoreLocalFileSystem();

    private final List<LazyDirectoryOrFile> myChildren = new ArrayList<>();

    private PreloadedDirectory(Path file) {
      super(FS, file, true);
    }

    @Override
    public @Nullable VirtualFile getParent() {
      return null;
    }

    @Override
    public @Nullable VirtualFile findChild(@NotNull String name) {
      if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) throw new IllegalArgumentException(name);
      return ContainerUtil.find(myChildren, f -> name.equals(f.getName()));
    }

    @Override
    public VirtualFile[] getChildren() {
      return VfsUtilCore.toVirtualFileArray(myChildren);
    }
  }

  private static class LazyDirectoryOrFile extends CoreLocalVirtualFile {
    private static final CoreLocalFileSystem FS = new CoreLocalFileSystem();

    private final @Nullable VirtualFile myParent;
    private final @Nullable Map<String, Optional<LazyDirectoryOrFile>> myChildren;

    private LazyDirectoryOrFile(@Nullable VirtualFile parent, Path file, BasicFileAttributes attrs) {
      super(FS, file, attrs);
      myParent = parent;
      myChildren = attrs.isDirectory() ? new HashMap<>() : null;
      if (parent instanceof PreloadedDirectory) {
        ((PreloadedDirectory)parent).myChildren.add(this);
      }
    }

    @Override
    public @Nullable VirtualFile getParent() {
      return myParent;
    }

    @Override
    public @Nullable VirtualFile findChild(@NotNull String name) {
      if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) throw new IllegalArgumentException(name);
      return myChildren == null ? null : myChildren.computeIfAbsent(name, k -> {
        try {
          var childFile = getFile().resolve(name);
          var attrs = Files.readAttributes(childFile, BasicFileAttributes.class);
          return Optional.of(new LazyDirectoryOrFile(this, childFile, attrs));
        }
        catch (Exception e) {
          LOG.trace(e);
          return Optional.empty();
        }
      }).orElse(null);
    }

    @Override
    public VirtualFile[] getChildren() {
      return myChildren == null ? VirtualFile.EMPTY_ARRAY
                                : myChildren.values().stream().map(o -> o.orElse(null)).filter(Objects::nonNull).toArray(VirtualFile[]::new);
    }
  }
}
