package com.illumine.woodcutter;

import com.tonic.Static;
import com.tonic.services.pathfinder.local.LocalCollisionMap;
import com.tonic.util.WorldPointUtil;
import net.runelite.api.Client;
import net.runelite.api.CollisionData;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

final class IllumineReach {
    private IllumineReach() {}

    static WorldPoint findApproach(WorldPoint from, TargetInfo target) {
        if (from.getPlane() != target.plane) return null;

        ArrayDeque<Integer> q = new ArrayDeque<>();
        Set<Integer> seen = new HashSet<>();
        int start = WorldPointUtil.compress(from.getX(), from.getY(), from.getPlane());
        q.add(start);
        seen.add(start);

        int explored = 0;
        final int LIMIT = 20000;

        while (!q.isEmpty() && explored < LIMIT) {
            int cur = q.poll();
            explored++;
            short x = WorldPointUtil.getCompressedX(cur);
            short y = WorldPointUtil.getCompressedY(cur);
            byte p = WorldPointUtil.getCompressedPlane(cur);

            if (reached(x, y, p, target)) {
                return new WorldPoint(x, y, p);
            }

            // Expand 4-neighborhood using raw collision flags (no door overrides)
            int f = tileFlags(x, y, p);
            // West
            if ((f & CollisionDataFlag.BLOCK_MOVEMENT_WEST) == 0 && notFull(x - 1, y, p))
                enqueue(seen, q, WorldPointUtil.compress(x - 1, y, p));
            // East
            if ((f & CollisionDataFlag.BLOCK_MOVEMENT_EAST) == 0 && notFull(x + 1, y, p))
                enqueue(seen, q, WorldPointUtil.compress(x + 1, y, p));
            // South
            if ((f & CollisionDataFlag.BLOCK_MOVEMENT_SOUTH) == 0 && notFull(x, y - 1, p))
                enqueue(seen, q, WorldPointUtil.compress(x, y - 1, p));
            // North
            if ((f & CollisionDataFlag.BLOCK_MOVEMENT_NORTH) == 0 && notFull(x, y + 1, p))
                enqueue(seen, q, WorldPointUtil.compress(x, y + 1, p));
        }

        return null;
    }

    private static void enqueue(Set<Integer> seen, ArrayDeque<Integer> q, int n) {
        if (seen.add(n)) q.add(n);
    }

    private static boolean reached(int srcX, int srcY, int plane, TargetInfo t) {
        // If target tile itself (for rectangles) is reached, accept unless exclusive
        boolean inside = collides(srcX, srcY, t.destX, t.destY, 1, 1, t.width, t.length);
        if (inside) return !t.exclusive; // actors: exclusive => must not collide

        // For size-1 source, use rectangle adjacency + edge checks (unblocked edges)
        return reachRectangle1(plane, srcX, srcY, t.destX, t.destY, t.width, t.length, t.blockAccessFlags);
    }

    private static boolean collides(int srcX, int srcY, int destX, int destY, int srcW, int srcL, int destW, int destL) {
        if (srcX >= destX + destW || srcX + srcW <= destX) return false;
        return srcY < destY + destL && destY < srcL + srcY;
    }

    private static boolean reachRectangle1(int plane,
                                           int srcX, int srcY,
                                           int destX, int destY,
                                           int destW, int destL,
                                           int blockAccessFlags) {
        final int east = destX + destW - 1;
        final int north = destY + destL - 1;

        // West of rectangle: need east edge from src unblocked
        if (srcX == destX - 1 && srcY >= destY && srcY <= north) {
            if (!blockedEast(srcX, srcY, plane) && !blockedWest(srcX + 1, srcY, plane) && (blockAccessFlags & 0x8) == 0) return true; // WEST flag blocks interaction
        }
        // East of rectangle: need west edge from src unblocked
        if (srcX == east + 1 && srcY >= destY && srcY <= north) {
            if (!blockedWest(srcX, srcY, plane) && !blockedEast(srcX - 1, srcY, plane) && (blockAccessFlags & 0x2) == 0) return true; // EAST flag
        }
        // South of rectangle: need north edge from src unblocked
        if (srcY + 1 == destY && srcX >= destX && srcX <= east) {
            if (!blockedNorth(srcX, srcY, plane) && !blockedSouth(srcX, srcY + 1, plane) && (blockAccessFlags & 0x4) == 0) return true; // SOUTH flag
        }
        // North of rectangle: need south edge from src unblocked
        if (srcY == north + 1 && srcX >= destX && srcX <= east) {
            if (!blockedSouth(srcX, srcY, plane) && !blockedNorth(srcX, srcY - 1, plane) && (blockAccessFlags & 0x1) == 0) return true; // NORTH flag
        }
        return false;
    }

    static TargetInfo fromWorldArea(WorldArea area, boolean exclusive) {
        WorldPoint base = area.toWorldPoint();
        return new TargetInfo(base.getX(), base.getY(), base.getPlane(), area.getWidth(), area.getHeight(), exclusive, 0);
    }

    static final class TargetInfo {
        final int destX;
        final int destY;
        final int plane;
        final int width;
        final int length;
        final boolean exclusive; // true for actors
        final int blockAccessFlags; // 4-bit NESW; 0 for now

        TargetInfo(int destX, int destY, int plane, int width, int length, boolean exclusive, int blockAccessFlags) {
            this.destX = destX;
            this.destY = destY;
            this.plane = plane;
            this.width = width;
            this.length = length;
            this.exclusive = exclusive;
            this.blockAccessFlags = blockAccessFlags;
        }
    }

    // ---- RuneLite collision flag helpers (source tile only) ----
    static int tileFlags(int worldX, int worldY, int plane) {
        Client client = Static.getClient();
        WorldView wv = client.getTopLevelWorldView();
        if (wv == null) return 0;
        CollisionData[] maps = wv.getCollisionMaps();
        if (maps == null || plane < 0 || plane >= maps.length || maps[plane] == null) return 0;
        LocalPoint lp = LocalPoint.fromWorld(wv, worldX, worldY);
        if (lp == null) return 0;
        int sx = lp.getSceneX();
        int sy = lp.getSceneY();
        int[][] flags = maps[plane].getFlags();
        if (sx < 0 || sy < 0 || sx >= flags.length || sy >= flags[sx].length) return 0;
        return flags[sx][sy];
    }

    static boolean blockedEast(int worldX, int worldY, int plane) {
        return (tileFlags(worldX, worldY, plane) & CollisionDataFlag.BLOCK_MOVEMENT_EAST) != 0;
    }
    static boolean blockedWest(int worldX, int worldY, int plane) {
        return (tileFlags(worldX, worldY, plane) & CollisionDataFlag.BLOCK_MOVEMENT_WEST) != 0;
    }
    static boolean blockedNorth(int worldX, int worldY, int plane) {
        return (tileFlags(worldX, worldY, plane) & CollisionDataFlag.BLOCK_MOVEMENT_NORTH) != 0;
    }
    static boolean blockedSouth(int worldX, int worldY, int plane) {
        return (tileFlags(worldX, worldY, plane) & CollisionDataFlag.BLOCK_MOVEMENT_SOUTH) != 0;
    }

    static boolean notFull(int worldX, int worldY, int plane) {
        return (tileFlags(worldX, worldY, plane) & CollisionDataFlag.BLOCK_MOVEMENT_FULL) == 0;
    }
}
