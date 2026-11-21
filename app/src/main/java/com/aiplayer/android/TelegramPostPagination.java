package com.aiplayer.android;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class TelegramPostPagination {
    private long oldestPost;

    TelegramPostPagination(List<TelegramPublicContent.Post> initialPosts) {
        oldestPost = initialPosts.stream().mapToLong(post -> postId(post.url))
                .filter(id -> id > 0).min().orElse(0);
    }

    long before() {
        return oldestPost;
    }

    boolean mayContain(long anchorId) {
        return anchorId > 0 && anchorId < oldestPost;
    }

    List<TelegramPublicContent.Post> acceptOlder(List<TelegramPublicContent.Post> posts) {
        long before = oldestPost;
        Set<Long> seen = new HashSet<>();
        List<TelegramPublicContent.Post> older = new ArrayList<>();
        for (TelegramPublicContent.Post post : posts) {
            long id = postId(post.url);
            if (id > 0 && id < before && seen.add(id)) {
                older.add(post);
                oldestPost = Math.min(oldestPost, id);
            }
        }
        return older;
    }

    static long postId(String url) {
        try {
            TelegramLink link = TelegramLink.parse(url);
            if (link.isPrivatePost()) {
                String path = link.url.getPath().replaceFirst("/$", "");
                return Long.parseLong(path.substring(path.lastIndexOf('/') + 1));
            }
            return Long.parseLong(link.source.postId);
        } catch (Exception invalid) {
            return 0;
        }
    }

    static long inviteMessageId(String url) {
        try {
            if (url.startsWith("tdlib:")) return Long.parseLong(url.substring(url.lastIndexOf('/') + 1));
            long id = postId(url);
            return id > 0 && id <= (Long.MAX_VALUE >> 20) ? id << 20 : 0;
        } catch (NumberFormatException invalid) {
            return 0;
        }
    }
}
