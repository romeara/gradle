/*
 * Copyright 2010 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.internal.tasks;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.io.Closer;
import com.google.common.io.Files;
import groovy.lang.Closure;
import org.apache.commons.io.FileUtils;
import org.gradle.api.Task;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.FileCollection;
import org.gradle.api.internal.TaskExecutionHistory;
import org.gradle.api.internal.TaskInternal;
import org.gradle.api.internal.TaskOutputsInternal;
import org.gradle.api.internal.file.FileResolver;
import org.gradle.api.internal.file.collections.DefaultConfigurableFileCollection;
import org.gradle.api.specs.AndSpec;
import org.gradle.api.specs.Spec;
import org.gradle.api.tasks.TaskOutputs;
import org.gradle.util.GFileUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Queue;

public class DefaultTaskOutputs implements TaskOutputsInternal {
    private static final String DEFAULT_PROPERTY_PREFIX = "$default$";
    private int defaultPropertyCount;

    private final Map<String, TaskPropertyOutputFiles> propertyFiles = Maps.newTreeMap();
    private AndSpec<TaskInternal> upToDateSpec = new AndSpec<TaskInternal>();
    private AndSpec<TaskInternal> cacheIfSpec = new AndSpec<TaskInternal>();
    private TaskExecutionHistory history;
    private final FileResolver resolver;
    private final TaskInternal task;
    private final TaskMutator taskMutator;
    private ConfigurableFileCollection files;

    public DefaultTaskOutputs(FileResolver resolver, TaskInternal task, TaskMutator taskMutator) {
        this.resolver = resolver;
        this.task = task;
        this.taskMutator = taskMutator;
    }

    @Override
    public Spec<? super TaskInternal> getUpToDateSpec() {
        return upToDateSpec;
    }

    @Override
    public boolean isCacheEnabled() {
        return !cacheIfSpec.getSpecs().isEmpty() && cacheIfSpec.isSatisfiedBy(task);
    }

    @Override
    public boolean isCacheAllowed() {
        // If there's nothing to cache, we don't allow caching
        if (propertyFiles.isEmpty()) {
            return false;
        }
        for (TaskPropertyOutputFiles output : propertyFiles.values()) {
            // When a single property refers to multiple outputs, we don't know how to cache those
            if (output instanceof MultiPathTaskPropertyOutputFiles) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void upToDateWhen(final Closure upToDateClosure) {
        taskMutator.mutate("TaskOutputs.upToDateWhen(Closure)", new Runnable() {
            @Override
            public void run() {
                upToDateSpec = upToDateSpec.and(upToDateClosure);
            }
        });
    }

    @Override
    public void upToDateWhen(final Spec<? super Task> spec) {
        taskMutator.mutate("TaskOutputs.upToDateWhen(Spec)", new Runnable() {
            @Override
            public void run() {
                upToDateSpec = upToDateSpec.and(spec);
            }
        });
    }

    @Override
    public void cacheIf(final Closure closure) {
        taskMutator.mutate("TaskOutputs.cacheIf(Closure)", new Runnable() {
            public void run() {
                cacheIfSpec = cacheIfSpec.and(closure);
            }
        });
    }

    @Override
    public void cacheIf(final Spec<? super Task> spec) {
        taskMutator.mutate("TaskOutputs.cacheIf(Spec)", new Runnable() {
            public void run() {
                cacheIfSpec = cacheIfSpec.and(spec);
            }
        });
    }

    @Override
    public boolean getHasOutput() {
        return !propertyFiles.isEmpty() || !upToDateSpec.getSpecs().isEmpty();
    }

    @Override
    public FileCollection getFiles() {
        if (files == null) {
            files = new DefaultConfigurableFileCollection(task + " output files", resolver, null)
                .builtBy(task);
            for (TaskPropertyOutputFiles output : propertyFiles.values()) {
                files.from(output.getPaths());
            }
        }
        return files;
    }

    @Override
    public Map<String, TaskPropertyOutputFiles> getPropertyFiles() {
        return Collections.unmodifiableMap(propertyFiles);
    }

    @Override
    public TaskOutputs files(final Object... paths) {
        taskMutator.mutate("TaskOutputs.files(Object...)", new Runnable() {
            @Override
            public void run() {
                addMultiplePropertyOutput(DEFAULT_PROPERTY_PREFIX + defaultPropertyCount++, paths);
            }
        });
        return this;
    }

    @Override
    public TaskOutputs includeFiles(final String property, final Object... paths) {
        taskMutator.mutate("TaskOutputs.includeFiles(String, Object...)", new Runnable() {
            @Override
            public void run() {
                addMultiplePropertyOutput(property, paths);
            }
        });
        return this;
    }

    @Override
    public TaskOutputs file(final Object path) {
        taskMutator.mutate("TaskOutputs.file(Object)", new Runnable() {
            @Override
            public void run() {
                addMultiplePropertyOutput(DEFAULT_PROPERTY_PREFIX + defaultPropertyCount++, path);
            }
        });
        return this;
    }

    @Override
    public TaskOutputs includeFile(final String property, final Object path) {
        taskMutator.mutate("TaskOutputs.includeFile(String, Object)", new Runnable() {
            @Override
            public void run() {
                addPropertyOutput(property, new OutputFileTaskPropertyOutputFiles(path, resolver));
            }
        });
        return this;
    }

    @Override
    public TaskOutputs dir(final Object path) {
        taskMutator.mutate("TaskOutputs.dir(Object)", new Runnable() {
            @Override
            public void run() {
                addMultiplePropertyOutput(DEFAULT_PROPERTY_PREFIX + defaultPropertyCount++, path);
            }
        });
        return this;
    }

    @Override
    public TaskOutputs includeDir(final String property, final Object path) {
        taskMutator.mutate("TaskOutputs.includeDir(String, FileOrderMode, Object)", new Runnable() {
            @Override
            public void run() {
                addPropertyOutput(property, new OutputDirectoryTaskPropertyOutputFiles(path, resolver));
            }
        });
        return this;
    }

    private void addPropertyOutput(String property, TaskPropertyOutputFiles output) {
        if (propertyFiles.containsKey(property)) {
            throw new IllegalStateException(String.format("Property '%s' is already registered as an output of %s", property, task));
        }
        propertyFiles.put(property, output);
    }

    private void addMultiplePropertyOutput(String property, Object... paths) {
        TaskPropertyOutputFiles output = propertyFiles.get(property);
        if (output == null) {
            propertyFiles.put(property, new MultiPathTaskPropertyOutputFiles(paths));
        } else {
            if (output instanceof MultiPathTaskPropertyOutputFiles) {
                ((MultiPathTaskPropertyOutputFiles) output).addPaths(paths);
            } else {
                throw new IllegalStateException(String.format("Property '%s' is already registered as an output of %s", property, task));
            }
        }
    }

    @Override
    public FileCollection getPreviousFiles() {
        if (history == null) {
            throw new IllegalStateException("Task history is currently not available for this task.");
        }
        return history.getOutputFiles();
    }

    @Override
    public void setHistory(TaskExecutionHistory history) {
        this.history = history;
    }

    private static class MultiPathTaskPropertyOutputFiles implements TaskPropertyOutputFiles {
        private final List<Object> paths;

        public MultiPathTaskPropertyOutputFiles(Object... paths) {
            this.paths = Lists.newArrayList(paths);
        }

        public void addPaths(Object... additionalPaths) {
            Collections.addAll(paths, additionalPaths);
        }

        @Override
        public Collection<Object> getPaths() {
            return paths;
        }

        @Override
        public void visitFiles(TaskOutputVisitor visitor) throws IOException {
            throw new UnsupportedOperationException();
        }

        @Override
        public TaskOutputVisitor getVisitor() {
            throw new UnsupportedOperationException();
        }
    }

    private static class OutputDirectoryTaskPropertyOutputFiles implements TaskPropertyOutputFiles {
        private final FileResolver resolver;
        private final Object outputDirectory;
        private File resolvedOutputDirectory;
        private final TaskOutputVisitor visitor;

        public OutputDirectoryTaskPropertyOutputFiles(Object outputDirectory, FileResolver resolver) {
            this.resolver = resolver;
            this.outputDirectory = outputDirectory;
            this.visitor = new TaskOutputVisitor() {
                @Override
                public void visitDirectory(String path) throws IOException {
                    FileUtils.forceMkdir(new File(getResolvedOutputDirectory(), path));
                }

                @Override
                public void visitFile(String path, InputStream data) throws IOException {
                    File resolved = getResolvedOutputDirectory();
                    FileUtils.forceMkdir(resolved);
                    File file = new File(resolved, path);

                    //noinspection ResultOfMethodCallIgnored
                    file.createNewFile();
                    Files.asByteSink(file).writeFrom(data);
                }
            };
        }

        private File getResolvedOutputDirectory() {
            if (resolvedOutputDirectory == null) {
                resolvedOutputDirectory = resolver.resolve(outputDirectory);
            }
            return resolvedOutputDirectory;
        }

        @Override
        public void visitFiles(TaskOutputVisitor visitor) throws IOException {
            ArrayDeque<RelativePath> queue = new ArrayDeque<RelativePath>();
            File outputDir = resolver.resolve(outputDirectory);
            addChildrenToQueue(queue, "", outputDir);
            while (!queue.isEmpty()) {
                RelativePath item = queue.removeFirst();
                String path = item.getPath();
                File file = item.getFile();
                // TODO:LPTR Do some consistent order here
                if (file.isDirectory()) {
                    visitor.visitDirectory(path);
                    addChildrenToQueue(queue, path + "/", file);
                } else {
                    Closer closer = Closer.create();
                    try {
                        visitor.visitFile(path, closer.register(new FileInputStream(file)));
                    } catch (Exception ex) {
                        throw closer.rethrow(ex);
                    } finally {
                        //noinspection ThrowFromFinallyBlock
                        closer.close();
                    }
                }
            }
        }

        private static void addChildrenToQueue(Queue<RelativePath> queue, String basePath, File directory) {
            File[] files = directory.listFiles();
            if (files == null) {
                // TODO Handle this better
                throw new NullPointerException();
            }
            for (File file : files) {
                StringBuilder path = new StringBuilder(basePath == null ? 0 : basePath.length() + 1 + file.getName().length());
                if (basePath != null) {
                    path.append(basePath);
                }
                path.append("/");
                path.append(file.getName());
                queue.add(new RelativePath(path.toString(), file));
            }
        }

        @Override
        public TaskOutputVisitor getVisitor() {
            return visitor;
        }

        @Override
        public Collection<Object> getPaths() {
            return Collections.singleton(outputDirectory);
        }
    }

    private static class OutputFileTaskPropertyOutputFiles implements TaskPropertyOutputFiles {
        private final Object outputFile;
        private final FileResolver resolver;
        private File resolvedOutputFile;
        private final TaskOutputVisitor visitor;

        public OutputFileTaskPropertyOutputFiles(Object outputFile, FileResolver resolver) {
            this.outputFile = outputFile;
            this.resolver = resolver;
            this.visitor = new TaskOutputVisitor() {
                @Override
                public void visitDirectory(String path) throws IOException {
                    throw new UnsupportedOperationException();
                }

                @Override
                public void visitFile(String path, InputStream data) throws IOException {
                    File resolved = getResolvedOutputFile();
                    File outputParent = resolved.getParentFile();
                    if (!outputParent.isDirectory()) {
                        if (outputParent.isFile()) {
                            throw new IOException(String.format("Cannot create directory '%s', file already exists", outputParent));
                        } else {
                            GFileUtils.mkdirs(outputParent);
                        }
                    }
                    //noinspection ResultOfMethodCallIgnored
                    resolved.createNewFile();
                    Files.asByteSink(resolved).writeFrom(data);
                }
            };
        }

        private File getResolvedOutputFile() {
            if (resolvedOutputFile == null) {
                resolvedOutputFile = resolver.resolve(outputFile);
            }
            return resolvedOutputFile;
        }

        @Override
        public Collection<Object> getPaths() {
            return Collections.singleton(outputFile);
        }

        @Override
        public void visitFiles(TaskOutputVisitor visitor) throws IOException {
            Closer closer = Closer.create();
            try {
                visitor.visitFile("the-file", closer.register(new FileInputStream(getResolvedOutputFile())));
            } catch (Exception ex) {
                throw closer.rethrow(ex);
            } finally {
                //noinspection ThrowFromFinallyBlock
                closer.close();
            }
        }

        @Override
        public TaskOutputVisitor getVisitor() {
            return visitor;
        }
    }

    private static class RelativePath {
        private final String path;
        private final File file;

        public RelativePath(String path, File file) {
            this.path = path;
            this.file = file;
        }

        public String getPath() {
            return path;
        }

        public File getFile() {
            return file;
        }
    }
}
