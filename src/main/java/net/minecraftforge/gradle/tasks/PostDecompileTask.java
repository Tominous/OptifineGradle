/*
 * A Gradle plugin for the creation of Minecraft mods and MinecraftForge plugins.
 * Copyright (C) 2013 Minecraft Forge
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301
 * USA
 */
package net.minecraftforge.gradle.tasks;

import java.io.File;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import net.minecraftforge.gradle.common.Constants;
import net.minecraftforge.gradle.util.caching.Cached;
import net.minecraftforge.gradle.util.mcp.FFPatcher;
import net.minecraftforge.gradle.util.mcp.FmlCleanup;
import net.minecraftforge.gradle.util.mcp.GLConstantFixer;
import net.minecraftforge.gradle.util.mcp.McpCleanup;
import net.minecraftforge.gradle.util.patching.ContextualPatch;
import net.minecraftforge.gradle.util.patching.ContextualPatch.HunkReport;
import net.minecraftforge.gradle.util.patching.ContextualPatch.PatchReport;
import net.minecraftforge.gradle.util.patching.ContextualPatch.PatchStatus;

import org.gradle.api.file.FileCollection;
import org.gradle.api.logging.LogLevel;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;

import com.aucguy.optifinegradle.Patching;
import com.github.abrarsyed.jastyle.ASFormatter;
import com.github.abrarsyed.jastyle.OptParser;
import com.google.common.base.Joiner;
import com.google.common.base.Throwables;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import com.google.common.io.Files;

public class PostDecompileTask extends AbstractEditJarTask
{
    @InputFile
    private Object                       inJar;

    private Object                       patchDir;

    @InputFile
    private Object                       astyleConfig;
    
    @InputFile
    @Optional
    private Object                       deobfuscatedClasses;
    
    @OutputFile
    @Cached
    private Object                       outJar;

    private static final Pattern         BEFORE      = Pattern.compile("(?m)((case|default).+(?:\\r\\n|\\r|\\n))(?:\\r\\n|\\r|\\n)");
    private static final Pattern         AFTER       = Pattern.compile("(?m)(?:\\r\\n|\\r|\\n)((?:\\r\\n|\\r|\\n)[ \\t]+(case|default))");

    private final Multimap<String, File> patchesMap  = ArrayListMultimap.create();
    private final List<PatchReport>      patchErrors = Lists.newArrayList();
    private final ASFormatter            formatter   = new ASFormatter();
    private GLConstantFixer              oglFixer;

    @Override
    public void doStuffBefore() throws Exception
    {
    	Set<String> ignoredPatches = Patching.getIgnoredPatches(this, getDeobfuscatedClasses());
    	
        for (File f : getPatches())
        {
            String name = f.getName();

            if (name.contains("Enum")) // because version of FF is awesome and dont need dat
                continue;
            
            if(Patching.shouldSkip(name, ignoredPatches, false)) continue;
            
            int patchIndex = name.indexOf(".patch");

            // 6 is the length of ".patch" + 3 to account for .## at the end of the file.
            if (patchIndex < 0 || patchIndex < name.length() - 9)
                continue;

            patchesMap.put(name.substring(0, patchIndex), f);
        }

        OptParser parser = new OptParser(formatter);
        parser.parseOptionFile(getAstyleConfig());

        oglFixer = new GLConstantFixer();
    }

    @Override
    public String asRead(String name, String file) throws Exception
    {
        getLogger().debug("Processing file: " + file);

        file = FFPatcher.processFile(file);

        // patch the file
        Collection<File> patchFiles = patchesMap.get(name.replace('/', '.'));
        if (!patchFiles.isEmpty())
        {
            getLogger().debug("applying MCP patches");
            ContextProvider provider = new ContextProvider(file);
            ContextualPatch patch = findPatch(patchFiles, provider);
            patchErrors.addAll(patch.patch(false));
            file = provider.getAsString();
        }

        getLogger().debug("processing comments");
        file = McpCleanup.stripComments(file);

        getLogger().debug("fixing imports comments");
        file = McpCleanup.fixImports(file);

        getLogger().debug("various other cleanup");
        file = McpCleanup.cleanup(file);

        getLogger().debug("fixing OGL constants");
        file = oglFixer.fixOGL(file);

        getLogger().debug("formatting source");
        Reader reader = new StringReader(file);
        Writer writer = new StringWriter();
        formatter.format(reader, writer);
        reader.close();
        writer.flush();
        writer.close();
        file = writer.toString();

        getLogger().debug("applying FML transformations");
        file = BEFORE.matcher(file).replaceAll("$1");
        file = AFTER.matcher(file).replaceAll("$1");
        file = FmlCleanup.renameClass(file);

        return file;
    }

    @Override
    public void doStuffAfter() throws Exception
    {
        boolean fuzzed = false;
        for (PatchReport report : patchErrors)
        {
            if (!report.getStatus().isSuccess())
            {
                //getLogger().log(LogLevel.ERROR, "Patching failed: " + report.getTarget(), report.getFailure());
                getLogger().error("Patching failed: " + report.getTarget());

                for (HunkReport hunk : report.getHunks())
                {
                    if (!hunk.getStatus().isSuccess())
                    {
                        getLogger().error("Hunk " + hunk.getHunkID() + " failed!");
                    }
                }

                Throwables.propagate(report.getFailure());
            }
            else if (report.getStatus() == PatchStatus.Fuzzed) // catch fuzzed patches
            {
                getLogger().log(LogLevel.INFO, "Patching fuzzed: " + report.getTarget(), report.getFailure());
                fuzzed = true;

                for (HunkReport hunk : report.getHunks())
                {
                    if (!hunk.getStatus().isSuccess())
                    {
                        getLogger().info("Hunk " + hunk.getHunkID() + " fuzzed " + hunk.getFuzz() + "!");
                    }
                }
            }
            else
            {
                getLogger().debug("Patch succeeded: " + report.getTarget());
            }
        }
        if (fuzzed)
            getLogger().lifecycle("Patches Fuzzed!");
    }

    private static ContextualPatch findPatch(Collection<File> files, ContextProvider provider) throws Exception
    {
        ContextualPatch patch = null;
        for (File f : files)
        {
            patch = ContextualPatch.create(Files.toString(f, Constants.CHARSET), provider);
            List<PatchReport> errors = patch.patch(true);

            boolean success = true;
            for (PatchReport rep : errors)
            {
                if (!rep.getStatus().isSuccess())
                    success = false;
            }
            if (success)
                break;
        }
        return patch;
    }

    public File getAstyleConfig()
    {
        return getProject().file(astyleConfig);
    }

    public void setAstyleConfig(Object astyleConfig)
    {
        this.astyleConfig = astyleConfig;
    }
    
    public File getDeobfuscatedClasses()
    {
        return deobfuscatedClasses == null ? null : getProject().file(deobfuscatedClasses);
    }

    public void setDeobfuscatedClasses(Object deobfuscatedClasses)
    {
        this.deobfuscatedClasses = deobfuscatedClasses;
    }

    @InputFiles
    public FileCollection getPatches()
    {
        return getProject().fileTree(patchDir);
    }

    public void setPatches(Object patchesDir)
    {
        this.patchDir = patchesDir;
    }

    /**
     * A private inner class to be used with the MCPPatches only.
     */
    private static class ContextProvider implements ContextualPatch.IContextProvider
    {
        private List<String> data;

        public ContextProvider(String file)
        {
            data = Constants.lines(file);
        }

        @Override
        public List<String> getData(String target)
        {
            List<String> out = new ArrayList<String>(data.size() + 5);
            out.addAll(data);
            return out;
        }

        @Override
        public void setData(String target, List<String> data)
        {
            this.data = data;
        }

        public String getAsString()
        {
            return Joiner.on(Constants.NEWLINE).join(data);
        }
    }

    //@formatter:off
    @Override
    public void doStuffMiddle(Map<String, String> sourceMap, Map<String, byte[]> resourceMap) throws Exception
    {
    }

    @Override
    protected boolean storeJarInRam()
    {
        return false;
    }
}
