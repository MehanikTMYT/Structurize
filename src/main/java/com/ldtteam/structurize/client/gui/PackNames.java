package com.ldtteam.structurize.client.gui;

import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.Locale;

/**
 * Display names for structure packs and their folders.
 * <p>
 * Pack names, descriptions and folder names come from pack.json and the directory layout, so they were always shown
 * verbatim in English. Each lookup first tries a language key and falls back to the original text, which keeps every
 * pack working unchanged while letting a language file (or a resource pack) translate them:
 * <ul>
 * <li>{@code structurize.pack.<pack>.name} / {@code .desc}, where {@code <pack>} is the pack name lowercased with
 * non-alphanumerics as {@code _} ("Lost Mesa City" -> {@code lost_mesa_city})</li>
 * <li>{@code structurize.pack.folder.<folder>} for a folder ("fundamentals")</li>
 * <li>{@code structurize.pack.category.<category>} for a pack category in the pack switcher</li>
 * </ul>
 */
public final class PackNames
{
    private PackNames()
    {
    }

    private static String keyPart(final String text)
    {
        return text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
    }

    private static MutableComponent lookup(final String key, final String fallback)
    {
        return I18n.exists(key) ? Component.translatable(key) : Component.literal(fallback);
    }

    public static MutableComponent pack(final String name)
    {
        return lookup("structurize.pack." + keyPart(name) + ".name", name);
    }

    /** Description written into every player's local pack.json by ClientStructurePackLoader. */
    private static final String LOCAL_PACK_DESC = "This is your local Structurepack. This is where all your scans go.";

    public static MutableComponent packDesc(final String name, final String desc)
    {
        if (LOCAL_PACK_DESC.equals(desc))
        {
            return lookup("structurize.pack.local.desc", desc);
        }
        return lookup("structurize.pack." + keyPart(name) + ".desc", desc);
    }

    public static MutableComponent folder(final String folder)
    {
        return lookup("structurize.pack.folder." + keyPart(folder), folder.isEmpty() ? folder : folder.substring(0, 1).toUpperCase(Locale.US) + folder.substring(1));
    }

    /**
     * Blueprint variant ("builder", "builderalt", "townhall2"...): {@code structurize.pack.variant.<name>}; when that
     * key is missing, a trailing "alt" is split off and reported as {@code structurize.pack.variant.alt_suffix}.
     */
    public static MutableComponent variant(final String name)
    {
        final String key = "structurize.pack.variant." + keyPart(name);
        if (I18n.exists(key))
        {
            return Component.translatable(key);
        }
        for (final String prefix : new String[] {"alternative", "alt_", "alt"})
        {
            if (name.startsWith(prefix) && name.length() > prefix.length())
            {
                final String baseKey = "structurize.pack.variant." + keyPart(name.substring(prefix.length()));
                if (I18n.exists(baseKey))
                {
                    return Component.translatable("structurize.pack.variant.alt_suffix", Component.translatable(baseKey));
                }
            }
        }
        if (name.endsWith("alt") && name.length() > 3)
        {
            final String baseKey = "structurize.pack.variant." + keyPart(name.substring(0, name.length() - 3));
            if (I18n.exists(baseKey))
            {
                return Component.translatable("structurize.pack.variant.alt_suffix", Component.translatable(baseKey));
            }
        }
        return Component.literal(name);
    }

    /**
     * Blueprint file name ("builder1", "townhallalt3"): the variant translated, the level digits kept.
     */
    public static MutableComponent blueprintFile(final String fileName)
    {
        final String base = fileName.replaceAll("[0-9]+$", "");
        final String digits = fileName.substring(base.length());
        final MutableComponent name = variant(base);
        return digits.isEmpty() ? name : name.append(" " + digits);
    }

    public static MutableComponent category(final String category)
    {
        return lookup("structurize.pack.category." + keyPart(category), category.isEmpty() ? category : category.substring(0, 1).toUpperCase(Locale.US) + category.substring(1));
    }

    /**
     * @return "Pack/folder/folder[/file]" with every segment translated where a key exists
     */
    public static MutableComponent path(final String pack, final String... segments)
    {
        final MutableComponent result = pack(pack);
        for (final String part : segments)
        {
            if (part == null || part.isEmpty())
            {
                continue;
            }
            for (final String folder : part.split("/"))
            {
                if (!folder.isEmpty())
                {
                    result.append("/").append(folder(folder));
                }
            }
        }
        return result;
    }

    /**
     * @return the pack's display name as plain text, for searching
     */
    public static String packText(final String name)
    {
        return pack(name).getString();
    }
}
