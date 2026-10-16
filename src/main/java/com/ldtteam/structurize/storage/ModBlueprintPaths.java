package com.ldtteam.structurize.storage;

import com.ldtteam.structurize.api.Log;
import net.neoforged.neoforgespi.language.IModInfo;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static com.ldtteam.structurize.api.constants.Constants.BLUEPRINT_FOLDER;

/**
 * PORT-26.1.2: replacement for the removed {@code IModFile#findResource(BLUEPRINT_FOLDER, modId)}.
 * <p>
 * FML 11 only exposes the mod's content roots. In a dev run those are directories (classes/resources), but for a
 * packaged mod the root is the jar file itself, so resolving {@code blueprints/<modid>} against it never exists and every
 * style shipped in a jar (MineColonies' own styles in production, style-pack mods such as Byzantine) was silently skipped.
 * Jar roots are opened as zip file systems, which stay open for the whole session because pack metas keep these paths and
 * blueprints are read from them lazily.
 */
public final class ModBlueprintPaths
{
    private static final Map<Path, FileSystem> JAR_FILE_SYSTEMS = new ConcurrentHashMap<>();

    private ModBlueprintPaths()
    {
    }

    /**
     * @param mod the mod to look into.
     * @return {@code blueprints/<modid>} inside the first content root of the mod that has it.
     */
    public static Optional<Path> find(final IModInfo mod)
    {
        for (final Path contentRoot : mod.getOwningFile().getFile().getContents().getContentRoots())
        {
            final Path base = Files.isRegularFile(contentRoot) ? jarRoot(contentRoot) : contentRoot;
            if (base == null)
            {
                continue;
            }

            final Path candidate = base.resolve(BLUEPRINT_FOLDER).resolve(mod.getModId());
            if (Files.isDirectory(candidate))
            {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private static Path jarRoot(final Path jar)
    {
        final FileSystem fileSystem = JAR_FILE_SYSTEMS.computeIfAbsent(jar, path -> {
            try
            {
                return FileSystems.newFileSystem(path);
            }
            catch (final IOException | RuntimeException e)
            {
                Log.getLogger().warn("Could not open mod jar to look for blueprints: " + path, e);
                return null;
            }
        });
        return fileSystem == null ? null : fileSystem.getPath("/");
    }
}
