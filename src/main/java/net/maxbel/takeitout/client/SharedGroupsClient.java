package net.maxbel.takeitout.client;

import net.maxbel.takeitout.Takeitout;

import java.util.ArrayList;
import java.util.List;

public final class SharedGroupsClient {
    public static final List<Takeitout.SharedGroupEntry> SHARED_GROUPS = new ArrayList<>();
    public static boolean serverSupportsSharedGroups = false;

    private SharedGroupsClient() {}

    public static void clear() {
        SHARED_GROUPS.clear();
        serverSupportsSharedGroups = false;
    }
}
