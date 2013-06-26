/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Eclipse Public License, Version 1.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.eclipse.org/org/documents/epl-v10.php
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.ide.eclipse.adt.internal.build;

import com.android.SdkConstants;
import com.android.annotations.NonNull;
import com.android.ide.eclipse.adt.AdtConstants;
import com.android.ide.eclipse.adt.AdtPlugin;
import com.android.ide.eclipse.adt.internal.build.builders.BaseBuilder;
import com.android.ide.eclipse.adt.internal.preferences.AdtPrefs;
import com.android.ide.eclipse.adt.internal.preferences.AdtPrefs.BuildVerbosity;
import com.android.ide.eclipse.adt.internal.project.BaseProjectHelper;
import com.android.resources.ResourceFolderType;
import com.android.sdklib.BuildToolInfo;
import com.android.sdklib.IAndroidTarget;
import com.google.common.collect.Sets;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.jdt.core.IJavaProject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A {@link SourceProcessor} for RenderScript files.
 *
 */
public class RenderScriptProcessor extends SourceProcessor {

    private static final String PROPERTY_COMPILE_RS = "compileRenderScript"; //$NON-NLS-1$

    /**
     * Single line llvm-rs-cc error: {@code <path>:<line>:<col>: <error>}
     */
    private static Pattern sLlvmPattern1 = Pattern.compile("^(.+?):(\\d+):(\\d+):\\s(.+)$"); //$NON-NLS-1$

    private final static Set<String> EXTENSIONS = Sets.newHashSetWithExpectedSize(2);
    static {
        EXTENSIONS.add(SdkConstants.EXT_RS);
        EXTENSIONS.add(SdkConstants.EXT_FS);
    }

    private static class RsChangeHandler extends SourceChangeHandler {

        @Override
        public boolean handleGeneratedFile(IFile file, int kind) {
            boolean r = super.handleGeneratedFile(file, kind);
            if (r == false &&
                    kind == IResourceDelta.REMOVED &&
                    SdkConstants.EXT_DEP.equalsIgnoreCase(file.getFileExtension())) {
                // This looks to be a dependency file.
                // For future-proofness let's make sure this dependency file was generated by
                // this processor even if it's the only processor using them for now.

                // look for the original file.
                // We know we are in the gen folder, so make a path to the dependency file
                // relative to the gen folder. Convert this into a Renderscript source file,
                // and look to see if this file exists.
                SourceProcessor processor = getProcessor();
                IFolder genFolder = processor.getGenFolder();
                IPath relative = file.getFullPath().makeRelativeTo(genFolder.getFullPath());
                // remove the file name segment
                relative = relative.removeLastSegments(1);
                // add the file name of a Renderscript file.
                relative = relative.append(file.getName().replaceAll(
                        AdtConstants.RE_DEP_EXT, SdkConstants.DOT_RS));

                if (!findInSourceFolders(processor, genFolder, relative)) {
                    // could be a FilterScript file?
                    relative = file.getFullPath().makeRelativeTo(genFolder.getFullPath());
                    // remove the file name segment
                    relative = relative.removeLastSegments(1);
                    // add the file name of a FilterScript file.
                    relative = relative.append(file.getName().replaceAll(
                            AdtConstants.RE_DEP_EXT, SdkConstants.DOT_FS));

                    return findInSourceFolders(processor, genFolder, relative);
                }

                return true;
            }

            return r;
        }

        private boolean findInSourceFolders(SourceProcessor processor, IFolder genFolder,
                IPath relative) {
            // now look for a match in the source folders.
            List<IPath> sourceFolders = BaseProjectHelper.getSourceClasspaths(
                    processor.getJavaProject());
            IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();

            for (IPath sourceFolderPath : sourceFolders) {
                IFolder sourceFolder = root.getFolder(sourceFolderPath);
                // we don't look in the 'gen' source folder as there will be no source in there.
                if (sourceFolder.exists() && sourceFolder.equals(genFolder) == false) {
                    IFile sourceFile = sourceFolder.getFile(relative);
                    SourceFileData data = processor.getFileData(sourceFile);
                    if (data != null) {
                        addFileToCompile(sourceFile);
                        return true;
                    }
                }
            }

            return false;
        }

        @Override
        protected boolean filterResourceFolder(IContainer folder) {
            return ResourceFolderType.RAW.getName().equals(folder.getName());
        }
    }

    private int mTargetApi = 11;

    public RenderScriptProcessor(@NonNull IJavaProject javaProject,
            @NonNull BuildToolInfo buildToolInfo, @NonNull IFolder genFolder) {
        super(javaProject, buildToolInfo, genFolder, new RsChangeHandler());
    }

    public void setTargetApi(int targetApi) {
        // make sure the target api value is good. Must be 11+ or llvm-rs-cc complains.
        mTargetApi = targetApi < 11 ? 11 : targetApi;
    }

    @Override
    protected Set<String> getExtensions() {
        return EXTENSIONS;
    }

    @Override
    protected String getSavePropertyName() {
        return PROPERTY_COMPILE_RS;
    }

    @Override
    protected void doCompileFiles(List<IFile> sources, BaseBuilder builder,
            IProject project, IAndroidTarget projectTarget,
            List<IPath> sourceFolders, List<IFile> notCompiledOut,  List<File> libraryProjectsOut,
            IProgressMonitor monitor) throws CoreException {

        IFolder genFolder = getGenFolder();

        IFolder rawFolder = project.getFolder(
                new Path(SdkConstants.FD_RES).append(SdkConstants.FD_RES_RAW));

        int depIndex;

        BuildToolInfo buildToolInfo = getBuildToolInfo();

        // create the command line
        String[] command = new String[15];
        int index = 0;
        command[index++] = quote(buildToolInfo.getPath(BuildToolInfo.PathId.LLVM_RS_CC));
        command[index++] = "-I";   //$NON-NLS-1$
        command[index++] = quote(buildToolInfo.getPath(BuildToolInfo.PathId.ANDROID_RS_CLANG));
        command[index++] = "-I";   //$NON-NLS-1$
        command[index++] = quote(buildToolInfo.getPath(BuildToolInfo.PathId.ANDROID_RS));
        command[index++] = "-p";   //$NON-NLS-1$
        command[index++] = quote(genFolder.getLocation().toOSString());
        command[index++] = "-o";   //$NON-NLS-1$
        command[index++] = quote(rawFolder.getLocation().toOSString());

        command[index++] = "-target-api";   //$NON-NLS-1$
        command[index++] = Integer.toString(mTargetApi);

        command[index++] = "-d";   //$NON-NLS-1$
        command[depIndex = index++] = null;
        command[index++] = "-MD";  //$NON-NLS-1$

        boolean verbose = AdtPrefs.getPrefs().getBuildVerbosity() == BuildVerbosity.VERBOSE;
        boolean someSuccess = false;

        // remove the generic marker from the project
        builder.removeMarkersFromResource(project, AdtConstants.MARKER_RENDERSCRIPT);

        // loop until we've compile them all
        for (IFile sourceFile : sources) {
            if (verbose) {
                String name = sourceFile.getName();
                IPath sourceFolderPath = getSourceFolderFor(sourceFile);
                if (sourceFolderPath != null) {
                    // make a path to the source file relative to the source folder.
                    IPath relative = sourceFile.getFullPath().makeRelativeTo(sourceFolderPath);
                    name = relative.toString();
                }
                AdtPlugin.printToConsole(project, "RenderScript: " + name);
            }

            // Remove the RS error markers from the source file and the dependencies
            builder.removeMarkersFromResource(sourceFile, AdtConstants.MARKER_RENDERSCRIPT);
            SourceFileData data = getFileData(sourceFile);
            if (data != null) {
                for (IFile dep : data.getDependencyFiles()) {
                    builder.removeMarkersFromResource(dep, AdtConstants.MARKER_RENDERSCRIPT);
                }
            }

            // get the path of the source file.
            IPath sourcePath = sourceFile.getLocation();
            String osSourcePath = sourcePath.toOSString();

            // finish to set the command line.
            command[depIndex] = quote(getDependencyFolder(sourceFile).getLocation().toOSString());
            command[index] = quote(osSourcePath);

            // launch the process
            if (execLlvmRsCc(builder, project, command, sourceFile, verbose) == false) {
                // llvm-rs-cc failed. File should be marked. We add the file to the list
                // of file that will need compilation again.
                notCompiledOut.add(sourceFile);
            } else {
                // Success. we'll return that we generated code and resources.
                setCompilationStatus(COMPILE_STATUS_CODE | COMPILE_STATUS_RES);

                // need to parse the .d file to figure out the dependencies and the generated file
                parseDependencyFileFor(sourceFile);
                someSuccess = true;
            }
        }

        if (someSuccess) {
            rawFolder.refreshLocal(IResource.DEPTH_ONE, monitor);
        }
    }

    private boolean execLlvmRsCc(BaseBuilder builder, IProject project, String[] command,
            IFile sourceFile, boolean verbose) {
        // do the exec
        try {
            if (verbose) {
                StringBuilder sb = new StringBuilder();
                for (String c : command) {
                    sb.append(c);
                    sb.append(' ');
                }
                String cmd_line = sb.toString();
                AdtPlugin.printToConsole(project, cmd_line);
            }

            Process p = Runtime.getRuntime().exec(command);

            // list to store each line of stderr
            ArrayList<String> stdErr = new ArrayList<String>();

            // get the output and return code from the process
            int returnCode = BuildHelper.grabProcessOutput(project, p, stdErr);

            if (stdErr.size() > 0) {
                // attempt to parse the error output
                boolean parsingError = parseLlvmOutput(stdErr);

                // If the process failed and we couldn't parse the output
                // we print a message, mark the project and exit
                if (returnCode != 0) {

                    if (parsingError || verbose) {
                        // display the message in the console.
                        if (parsingError) {
                            AdtPlugin.printErrorToConsole(project, stdErr.toArray());

                            // mark the project
                            BaseProjectHelper.markResource(project,
                                    AdtConstants.MARKER_RENDERSCRIPT,
                                    "Unparsed Renderscript error! Check the console for output.",
                                    IMarker.SEVERITY_ERROR);
                        } else {
                            AdtPlugin.printToConsole(project, stdErr.toArray());
                        }
                    }
                    return false;
                }
            } else if (returnCode != 0) {
                // no stderr output but exec failed.
                String msg = String.format("Error executing Renderscript: Return code %1$d",
                        returnCode);

                BaseProjectHelper.markResource(project, AdtConstants.MARKER_AIDL,
                       msg, IMarker.SEVERITY_ERROR);

                return false;
            }
        } catch (IOException e) {
            // mark the project and exit
            String msg = String.format(
                    "Error executing Renderscript. Please check llvm-rs-cc is present at %1$s",
                    command[0]);
            BaseProjectHelper.markResource(project, AdtConstants.MARKER_RENDERSCRIPT, msg,
                    IMarker.SEVERITY_ERROR);
            return false;
        } catch (InterruptedException e) {
            // mark the project and exit
            String msg = String.format(
                    "Error executing Renderscript. Please check llvm-rs-cc is present at %1$s",
                    command[0]);
            BaseProjectHelper.markResource(project, AdtConstants.MARKER_RENDERSCRIPT, msg,
                    IMarker.SEVERITY_ERROR);
            return false;
        }

        return true;
    }

    /**
     * Parse the output of llvm-rs-cc and mark the file with any errors.
     * @param lines The output to parse.
     * @return true if the parsing failed, false if success.
     */
    private boolean parseLlvmOutput(ArrayList<String> lines) {
        // nothing to parse? just return false;
        if (lines.size() == 0) {
            return false;
        }

        // get the root folder for the project as we're going to ignore everything that's
        // not in the project
        IProject project = getJavaProject().getProject();
        String rootPath = project.getLocation().toOSString();
        int rootPathLength = rootPath.length();

        Matcher m;

        boolean parsing = false;

        for (int i = 0; i < lines.size(); i++) {
            String p = lines.get(i);

            m = sLlvmPattern1.matcher(p);
            if (m.matches()) {
                // get the file path. This may, or may not be the main file being compiled.
                String filePath = m.group(1);
                if (filePath.startsWith(rootPath) == false) {
                    // looks like the error in a non-project file. Keep parsing, but
                    // we'll return true
                    parsing = true;
                    continue;
                }

                // get the actual file.
                filePath = filePath.substring(rootPathLength);
                // remove starting separator since we want the path to be relative
                if (filePath.startsWith(File.separator)) {
                    filePath = filePath.substring(1);
                }

                // get the file
                IFile f = project.getFile(new Path(filePath));

                String lineStr = m.group(2);
                // ignore group 3 for now, this is the col number
                String msg = m.group(4);

                // get the line number
                int line = 0;
                try {
                    line = Integer.parseInt(lineStr);
                } catch (NumberFormatException e) {
                    // looks like the string we extracted wasn't a valid
                    // file number. Parsing failed and we return true
                    return true;
                }

                // mark the file
                BaseProjectHelper.markResource(f, AdtConstants.MARKER_RENDERSCRIPT, msg, line,
                        IMarker.SEVERITY_ERROR);

                // success, go to the next line
                continue;
            }

            // invalid line format, flag as error, and keep going
            parsing = true;
        }

        return parsing;
    }


    @Override
    protected void doRemoveFiles(SourceFileData bundle) throws CoreException {
        // call the super implementation, it will remove the output files
        super.doRemoveFiles(bundle);

        // now remove the dependency file.
        IFile depFile = getDependencyFileFor(bundle.getSourceFile());
        if (depFile.exists()) {
            depFile.getLocation().toFile().delete();
        }
    }

    @Override
    protected void loadOutputAndDependencies() {
        Collection<SourceFileData> dataList = getAllFileData();
        for (SourceFileData data : dataList) {
            // parse the dependency file. If this fails, force compilation of the file.
            if (parseDependencyFileFor(data.getSourceFile()) == false) {
                addFileToCompile(data.getSourceFile());
            }
        }
    }

    private boolean parseDependencyFileFor(IFile sourceFile) {
        IFile depFile = getDependencyFileFor(sourceFile);
        File f = depFile.getLocation().toFile();
        if (f.exists()) {
            SourceFileData data = getFileData(sourceFile);
            if (data == null) {
                data = new SourceFileData(sourceFile);
                addData(data);
            }
            parseDependencyFile(data, f);
            return true;
        }

        return false;
    }

    private IFolder getDependencyFolder(IFile sourceFile) {
        IPath sourceFolderPath = getSourceFolderFor(sourceFile);

        // this really shouldn't happen since the sourceFile must be in a source folder
        // since it comes from the delta visitor
        if (sourceFolderPath != null) {
            // make a path to the source file relative to the source folder.
            IPath relative = sourceFile.getFullPath().makeRelativeTo(sourceFolderPath);
            // remove the file name. This is now the destination folder.
            relative = relative.removeLastSegments(1);

            return getGenFolder().getFolder(relative);
        }

        return null;
    }

    private IFile getDependencyFileFor(IFile sourceFile) {
        IFolder depFolder = getDependencyFolder(sourceFile);
        return depFolder.getFile(sourceFile.getName().replaceAll(AdtConstants.RE_RS_EXT,
                SdkConstants.DOT_DEP));
    }

    /**
     * Parses the given dependency file and fills the given {@link SourceFileData} with it.
     *
     * @param data the bundle to fill.
     * @param file the dependency file
     */
    private void parseDependencyFile(SourceFileData data, File dependencyFile) {
        //contents = file.getContents();
        String content = AdtPlugin.readFile(dependencyFile);

        // we're going to be pretty brutal here.
        // The format is something like:
        // output1 output2 [...]: dep1 dep2 [...]
        // expect it's likely split on several lines. So let's move it back on a single line
        // first
        String[] lines = content.split("\n"); //$NON-NLS-1$
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            line = line.trim();
            if (line.endsWith("\\")) { //$NON-NLS-1$
                line = line.substring(0, line.length() - 1);
            }

            sb.append(line);
        }

        // split the left and right part
        String[] files = sb.toString().split(":"); //$NON-NLS-1$

        // get the output files:
        String[] outputs = files[0].trim().split(" "); //$NON-NLS-1$

        // and the dependency files:
        String[] dependencies = files[1].trim().split(" "); //$NON-NLS-1$

        List<IFile> outputFiles = new ArrayList<IFile>();
        List<IFile> dependencyFiles = new ArrayList<IFile>();

        fillList(outputs, outputFiles);
        fillList(dependencies, dependencyFiles);

        data.setOutputFiles(outputFiles);
        data.setDependencyFiles(dependencyFiles);
    }

    private void fillList(String[] paths, List<IFile> list) {
        // get the root folder for the project as we're going to ignore everything that's
        // not in the project
        IProject project = getJavaProject().getProject();
        String rootPath = project.getLocation().toOSString();
        int rootPathLength = rootPath.length();

        // all those should really be in the project
        for (String p : paths) {

            if (p.startsWith(rootPath)) {
                p = p.substring(rootPathLength);
                // remove starting separator since we want the path to be relative
                if (p.startsWith(File.separator)) {
                    p = p.substring(1);
                }

                // get the file
                IFile f = project.getFile(new Path(p));
                list.add(f);
            }
        }
    }
}
