package org.telegram.messenger;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.media.MediaMetadataRetriever;

import com.sousa.feature_avatar.bridge.TelegramStickerPackSyncCallback;
import com.sousa.feature_avatar.bridge.TelegramStickerPackSyncRequest;
import com.sousa.feature_avatar.bridge.TelegramStickerPackUnit;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class AvatarStickerSetSyncController implements TelegramStickerPackSyncCallback {
    private static final String TAG = "AvatarStickerSync";
    private static final String SHORT_NAME_PREFIX = "teamgram_avatar_";
    // Reactions need a custom-emoji set, which is a different set kind than the sticker pack the
    // sticker panel shows, and a set's kind is fixed at creation. So the avatar pack is synced
    // twice, under two prefixes, and the reaction picker prefers the emoji one.
    private static final String EMOJI_SHORT_NAME_PREFIX = "teamgram_avataremoji_";
    private static final String SOFTWARE = "Teamgram Avatar";
    private static final String PREF_SET_SHORT_NAME = "avatar_reaction_set_short_name";
    private static final String PREF_EMOJI_SET_SHORT_NAME = "avatar_reaction_emoji_set_short_name";
    // Telegram rejects a custom emoji at any other size (STICKER_PNG_DIMENSIONS), while the sticker
    // pack keeps the 512px frames the avatar module renders.
    private static final int CUSTOM_EMOJI_SIZE = 100;
    private static final int STICKER_SIZE = 512;
    // Answered by the server on 2026-09-09: uploading the pack's 512px webm into an emoji set is
    // refused with STICKER_VIDEO_DIMENSIONS, and no 100px webm exists to send instead. So animated
    // emotions enter the emoji set as their opening frame — the M5 static fallback — and become
    // real animations once the avatar service emits a 100px variant.

    private final int currentAccount;

    public AvatarStickerSetSyncController(int currentAccount) {
        this.currentAccount = currentAccount;
    }

    @Override
    public void syncTelegramStickerPack(TelegramStickerPackSyncRequest request) {
        if (request == null || request.getUnits().isEmpty()) {
            return;
        }
        AndroidUtilities.runOnUIThread(() -> syncOnUiThread(request));
    }

    private void syncOnUiThread(TelegramStickerPackSyncRequest request) {
        long telegramUserId = UserConfig.getInstance(currentAccount).getClientUserId();
        if (telegramUserId == 0) {
            FileLog.w(TAG + ": skipped, Telegram user is not activated");
            return;
        }
        // The sticker pack first, so the panel keeps working even if the emoji set is rejected;
        // then the emoji set the reaction picker prefers.
        syncVariant(request, telegramUserId, false, () ->
                syncVariant(request, telegramUserId, true, null));
    }

    private void syncVariant(
            TelegramStickerPackSyncRequest request,
            long telegramUserId,
            boolean emojiSet,
            Runnable onDone
    ) {
        findMyStickerSet(request, shortName(telegramUserId, request.getAvatarId(), emojiSet), 0, emojiSet, onDone);
    }

    private static void runDone(Runnable onDone) {
        if (onDone != null) {
            onDone.run();
        }
    }

    private void findMyStickerSet(TelegramStickerPackSyncRequest request, String shortName, long offsetId, boolean emojiSet, Runnable onDone) {
        TLRPC.TL_messages_getMyStickers getMyStickers = new TLRPC.TL_messages_getMyStickers();
        getMyStickers.offset_id = offsetId;
        getMyStickers.limit = 100;
        ConnectionsManager.getInstance(currentAccount).sendRequest(getMyStickers, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (error != null) {
                FileLog.w(TAG + ": getMyStickers failed " + error.text);
                runDone(onDone);
                return;
            }
            TLRPC.StickerSetCovered covered = findSet(response, shortName);
            if (covered != null) {
                loadStickerSetAndUpdate(request, covered, emojiSet, onDone);
                return;
            }
            if (response instanceof TLRPC.TL_messages_myStickers) {
                TLRPC.TL_messages_myStickers myStickers = (TLRPC.TL_messages_myStickers) response;
                if (myStickers.sets.size() >= getMyStickers.limit) {
                    TLRPC.StickerSetCovered lastSet = myStickers.sets.get(myStickers.sets.size() - 1);
                    if (lastSet.set != null && lastSet.set.id != 0 && lastSet.set.id != offsetId) {
                        findMyStickerSet(request, shortName, lastSet.set.id, emojiSet, onDone);
                        return;
                    }
                }
            }
            createStickerSet(request, shortName, emojiSet, onDone);
        }));
    }

    private void createStickerSet(TelegramStickerPackSyncRequest request, String shortName, boolean emojiSet, Runnable onDone) {
        uploadUnits(request.getUnits(), new ArrayList<>(), 0, emojiSet, inputItems -> {
            boolean complete = inputItems.size() == request.getUnits().size();
            // An emoji set may legitimately come up short: an animated unit Telegram refuses is
            // still worth a set holding the rest, so the static emotions keep working. The sticker
            // pack stays all-or-nothing.
            if (!complete && !emojiSet) {
                FileLog.w(TAG + ": create skipped, uploaded " + inputItems.size() + " of " + request.getUnits().size());
                runDone(onDone);
                return;
            }
            if (inputItems.isEmpty()) {
                FileLog.w(TAG + ": create skipped, nothing uploaded for " + shortName);
                runDone(onDone);
                return;
            }
            if (!complete) {
                FileLog.w(TAG + ": creating " + shortName + " with " + inputItems.size() + " of " + request.getUnits().size() + " units");
            }
            TLRPC.TL_stickers_createStickerSet create = new TLRPC.TL_stickers_createStickerSet();
            create.user_id = new TLRPC.TL_inputUserSelf();
            create.title = request.getTitle();
            create.short_name = shortName;
            create.software = SOFTWARE;
            create.flags |= 8;
            create.emojis = emojiSet;
            create.stickers.addAll(inputItems);
            ConnectionsManager.getInstance(currentAccount).sendRequest(create, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                if (response instanceof TLRPC.TL_messages_stickerSet) {
                    TLRPC.TL_messages_stickerSet set = (TLRPC.TL_messages_stickerSet) response;
                    MediaDataController.getInstance(currentAccount).putStickerSet(set);
                    installIfNeeded(set);
                    rememberSyncedSet(set, emojiSet);
                    FileLog.d(TAG + ": created " + shortName + " emojis=" + emojiSet);
                } else if (error != null) {
                    // The emoji-set path is the one that can fail on asset requirements the sticker
                    // pack does not have, so the server text is the whole diagnostic here.
                    FileLog.w(TAG + ": create failed " + shortName + " emojis=" + emojiSet + " " + error.text);
                }
                runDone(onDone);
            }));
        });
    }

    private void loadStickerSetAndUpdate(TelegramStickerPackSyncRequest request, TLRPC.StickerSetCovered covered, boolean emojiSet, Runnable onDone) {
        TLRPC.TL_messages_getStickerSet getStickerSet = new TLRPC.TL_messages_getStickerSet();
        getStickerSet.stickerset = MediaDataController.getInputStickerSet(covered.set);
        getStickerSet.hash = 0;
        ConnectionsManager.getInstance(currentAccount).sendRequest(getStickerSet, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (response instanceof TLRPC.TL_messages_stickerSet) {
                updateStickerSet(request, (TLRPC.TL_messages_stickerSet) response, emojiSet, onDone);
            } else {
                if (error != null) {
                    FileLog.w(TAG + ": getStickerSet failed " + error.text);
                }
                runDone(onDone);
            }
        }));
    }

    private void updateStickerSet(TelegramStickerPackSyncRequest request, TLRPC.TL_messages_stickerSet set, boolean emojiSet, Runnable onDone) {
        MediaDataController.getInstance(currentAccount).putStickerSet(set);
        HashMap<String, TLRPC.Document> existingByEmoji = new HashMap<>();
        for (int i = 0; i < set.documents.size(); i++) {
            TLRPC.Document document = set.documents.get(i);
            String emoji = stickerEmoji(document);
            if (emoji != null) {
                existingByEmoji.put(emoji, document);
            }
        }
        updateUnitAt(request.getUnits(), existingByEmoji, 0, set, emojiSet, onDone);
    }

    private void updateUnitAt(List<TelegramStickerPackUnit> units, HashMap<String, TLRPC.Document> existingByEmoji, int index, TLRPC.TL_messages_stickerSet set, boolean emojiSet, Runnable onDone) {
        if (index >= units.size()) {
            installIfNeeded(set);
            rememberSyncedSet(set, emojiSet);
            FileLog.d(TAG + ": updated " + set.set.short_name);
            runDone(onDone);
            return;
        }
        TelegramStickerPackUnit unit = units.get(index);
        uploadUnit(unit, emojiSet, inputItem -> {
            if (inputItem == null) {
                updateUnitAt(units, existingByEmoji, index + 1, set, emojiSet, onDone);
                return;
            }
            TLRPC.Document oldDocument = existingByEmoji.get(inputItem.emoji);
            if (oldDocument != null && oldDocument.id == inputItem.document.id) {
                // Telegram dedupes uploads by content, so an unchanged frame comes back as the very
                // document already in the set. Replacing it with itself removes it first and then
                // fails with STICKER_ALREADY_DELETED, so leave it alone.
                updateUnitAt(units, existingByEmoji, index + 1, set, emojiSet, onDone);
                return;
            }
            TLObject req;
            if (oldDocument != null) {
                TLRPC.TL_stickers_replaceSticker replace = new TLRPC.TL_stickers_replaceSticker();
                replace.sticker = MediaDataController.getInputStickerSetItem(oldDocument, inputItem.emoji).document;
                replace.new_sticker = inputItem;
                req = replace;
            } else {
                TLRPC.TL_stickers_addStickerToSet add = new TLRPC.TL_stickers_addStickerToSet();
                add.stickerset = MediaDataController.getInputStickerSet(set.set);
                add.sticker = inputItem;
                req = add;
            }
            ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                if (response instanceof TLRPC.TL_messages_stickerSet) {
                    TLRPC.TL_messages_stickerSet updatedSet = (TLRPC.TL_messages_stickerSet) response;
                    MediaDataController.getInstance(currentAccount).putStickerSet(updatedSet);
                    updateUnitAt(units, existingByEmoji, index + 1, updatedSet, emojiSet, onDone);
                } else {
                    if (error != null) {
                        FileLog.w(TAG + ": update unit " + unit.getEmotionId() + " failed " + error.text);
                    }
                    updateUnitAt(units, existingByEmoji, index + 1, set, emojiSet, onDone);
                }
            }));
        });
    }

    private void uploadUnits(List<TelegramStickerPackUnit> units, ArrayList<TLRPC.TL_inputStickerSetItem> uploaded, int index, boolean emojiSet, Utilities.Callback<ArrayList<TLRPC.TL_inputStickerSetItem>> done) {
        // The cursor has to be its own index rather than uploaded.size(): a unit that yields no item
        // (a skipped animated emotion, a failed upload) would otherwise be retried forever.
        if (index >= units.size()) {
            done.run(uploaded);
            return;
        }
        TelegramStickerPackUnit unit = units.get(index);
        uploadUnit(unit, emojiSet, item -> {
            if (item != null) {
                uploaded.add(item);
            }
            uploadUnits(units, uploaded, index + 1, emojiSet, done);
        });
    }

    private void uploadUnit(TelegramStickerPackUnit unit, boolean emojiSet, Utilities.Callback<TLRPC.TL_inputStickerSetItem> done) {
        File file = new File(unit.getFilePath());
        if (!file.isFile() || file.length() == 0) {
            FileLog.w(TAG + ": missing file for " + unit.getEmotionId() + " path=" + unit.getFilePath());
            done.run(null);
            return;
        }
        if (!emojiSet) {
            startUpload(unit, unit.getFilePath(), false, null, done);
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            File scaled = scaleForCustomEmoji(emojiSource(unit, file), unit);
            AndroidUtilities.runOnUIThread(() -> {
                if (scaled == null) {
                    done.run(null);
                    return;
                }
                startUpload(unit, scaled.getAbsolutePath(), true, scaled, done);
            });
        });
    }

    private void startUpload(
            TelegramStickerPackUnit unit,
            String path,
            boolean emojiSet,
            File tempFile,
            Utilities.Callback<TLRPC.TL_inputStickerSetItem> done
    ) {
        FileLoader.getInstance(currentAccount).uploadFile(path, inputFile -> {
            if (inputFile == null) {
                FileLog.w(TAG + ": uploadFile failed for " + unit.getEmotionId());
                deleteTemp(tempFile);
                done.run(null);
                return;
            }
            uploadMedia(unit, inputFile, emojiSet, item -> {
                deleteTemp(tempFile);
                done.run(item);
            });
        });
    }

    /**
     * Picks what a custom emoji is built from. An animated emotion ships as a webm, but the module
     * also hands over its transparent still, and that still is the only source whose alpha survives
     * — pulling a frame out of the video flattens it onto black.
     */
    private static File emojiSource(TelegramStickerPackUnit unit, File packaged) {
        String staticFrame = unit.getStaticFramePath();
        if (staticFrame == null) {
            return packaged;
        }
        File frame = new File(staticFrame);
        if (!frame.isFile() || frame.length() == 0) {
            FileLog.w(TAG + ": no still frame on disk for " + unit.getEmotionId() + " at " + staticFrame);
            return packaged;
        }
        return frame;
    }

    /** Rescales one avatar frame to the size Telegram accepts for a custom emoji. */
    private File scaleForCustomEmoji(File source, TelegramStickerPackUnit unit) {
        Bitmap decoded = null;
        Bitmap scaled = null;
        try {
            decoded = decodeEmojiFrame(source, unit);
            if (decoded == null) {
                FileLog.w(TAG + ": could not decode " + unit.getEmotionId() + " for the emoji set");
                return null;
            }
            scaled = Bitmap.createScaledBitmap(decoded, CUSTOM_EMOJI_SIZE, CUSTOM_EMOJI_SIZE, true);
            File target = new File(
                    FileLoader.getDirectory(FileLoader.MEDIA_DIR_CACHE),
                    "avatar_emoji_" + unit.getEmotionId() + "_" + CUSTOM_EMOJI_SIZE + ".webp"
            );
            try (FileOutputStream output = new FileOutputStream(target)) {
                // Quality 100 keeps the encoder lossless: the avatar frames are transparent and a
                // lossy pass chews the alpha edges.
                if (!scaled.compress(Bitmap.CompressFormat.WEBP, 100, output)) {
                    FileLog.w(TAG + ": could not encode " + unit.getEmotionId() + " for the emoji set");
                    return null;
                }
            }
            return target;
        } catch (Exception error) {
            FileLog.e(error);
            return null;
        } finally {
            if (scaled != null && scaled != decoded) {
                scaled.recycle();
            }
            if (decoded != null) {
                decoded.recycle();
            }
        }
    }

    /**
     * Normally the source is already a transparent still and decodes directly. Extracting the
     * opening frame from a webm is the last resort, for a module build that sends no still: the
     * alpha does not survive it, so the result is checked and reported rather than shipped silently.
     */
    private static Bitmap decodeEmojiFrame(File source, TelegramStickerPackUnit unit) {
        if (!source.getAbsolutePath().endsWith(".webm")) {
            return BitmapFactory.decodeFile(source.getAbsolutePath());
        }
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(source.getAbsolutePath());
            Bitmap frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame == null) {
                FileLog.w(TAG + ": no first frame in " + source.getAbsolutePath());
                return null;
            }
            FileLog.d(TAG + ": " + unit.getEmotionId() + " falls back to its first frame, "
                    + (looksTransparent(frame) ? "transparency kept" : "TRANSPARENCY LOST"));
            return frame;
        } catch (Exception error) {
            FileLog.e(error);
            return null;
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
                // Releasing a retriever that never opened anything is not worth reporting.
            }
        }
    }

    /** Corner sampling: an avatar sits in the middle, so opaque corners mean the alpha is gone. */
    private static boolean looksTransparent(Bitmap bitmap) {
        int right = bitmap.getWidth() - 1;
        int bottom = bitmap.getHeight() - 1;
        return Color.alpha(bitmap.getPixel(0, 0)) < 255
                || Color.alpha(bitmap.getPixel(right, 0)) < 255
                || Color.alpha(bitmap.getPixel(0, bottom)) < 255
                || Color.alpha(bitmap.getPixel(right, bottom)) < 255;
    }

    private static void deleteTemp(File file) {
        if (file == null) {
            return;
        }
        try {
            file.delete();
        } catch (Exception ignored) {
            // A leftover file in the cache directory is not worth failing the sync over.
        }
    }

    private void uploadMedia(TelegramStickerPackUnit unit, TLRPC.InputFile inputFile, boolean emojiSet, Utilities.Callback<TLRPC.TL_inputStickerSetItem> done) {
        TLRPC.TL_messages_uploadMedia uploadMedia = new TLRPC.TL_messages_uploadMedia();
        uploadMedia.peer = new TLRPC.TL_inputPeerSelf();
        TLRPC.TL_inputMediaUploadedDocument media = new TLRPC.TL_inputMediaUploadedDocument();
        media.file = inputFile;
        // Every emoji-set unit is rescaled to a static webp first, so carrying the unit's original
        // video mime and attributes would describe a file we are not sending, and the server calls
        // that out as STICKER_VIDEO_NOWEBM.
        media.mime_type = emojiSet ? "image/webp" : unit.getMimeType();
        if (!emojiSet && "video/webm".equals(unit.getMimeType())) {
            media.nosound_video = true;
            // Video is uploaded untouched, so the attributes have to describe the actual file:
            // declaring a size it does not have would make a rejection say the wrong thing.
            TLRPC.TL_documentAttributeVideo videoAttr = new TLRPC.TL_documentAttributeVideo();
            videoAttr.nosound = true;
            describeVideo(unit.getFilePath(), videoAttr);
            media.attributes.add(videoAttr);
        }
        TLRPC.TL_documentAttributeSticker attr = new TLRPC.TL_documentAttributeSticker();
        attr.alt = emojiForEmotion(unit.getEmotionId());
        attr.stickerset = new TLRPC.TL_inputStickerSetEmpty();
        media.attributes.add(attr);
        if (emojiSet) {
            // Declared up front so the upload matches the set kind; the server is the authority and
            // fills this in itself once the set is created with emojis=true.
            TLRPC.TL_documentAttributeCustomEmoji emojiAttr = new TLRPC.TL_documentAttributeCustomEmoji();
            emojiAttr.alt = attr.alt;
            emojiAttr.stickerset = new TLRPC.TL_inputStickerSetEmpty();
            media.attributes.add(emojiAttr);
        }
        uploadMedia.media = media;
        ConnectionsManager.getInstance(currentAccount).sendRequest(uploadMedia, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (response instanceof TLRPC.TL_messageMediaDocument) {
                TLRPC.Document document = ((TLRPC.TL_messageMediaDocument) response).document;
                done.run(MediaDataController.getInputStickerSetItem(document, attr.alt));
            } else {
                if (error != null) {
                    FileLog.w(TAG + ": uploadMedia failed for " + unit.getEmotionId() + " " + error.text);
                }
                done.run(null);
            }
        }), ConnectionsManager.RequestFlagFailOnServerErrors);
    }

    private static void describeVideo(String path, TLRPC.TL_documentAttributeVideo videoAttr) {
        videoAttr.w = STICKER_SIZE;
        videoAttr.h = STICKER_SIZE;
        videoAttr.duration = 1.0;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(path);
            int width = metadataInt(retriever, MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            int height = metadataInt(retriever, MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            int durationMs = metadataInt(retriever, MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (width > 0 && height > 0) {
                videoAttr.w = width;
                videoAttr.h = height;
            }
            if (durationMs > 0) {
                videoAttr.duration = durationMs / 1000.0;
            }
            FileLog.d(TAG + ": video " + path + " is " + videoAttr.w + "x" + videoAttr.h
                    + " " + videoAttr.duration + "s");
        } catch (Exception error) {
            FileLog.w(TAG + ": could not read video metadata for " + path);
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
                // Releasing a retriever that never opened anything is not worth reporting.
            }
        }
    }

    private static int metadataInt(MediaMetadataRetriever retriever, int key) {
        try {
            return Integer.parseInt(retriever.extractMetadata(key));
        } catch (Exception error) {
            return 0;
        }
    }

    private void installIfNeeded(TLRPC.TL_messages_stickerSet set) {
        if (!MediaDataController.getInstance(currentAccount).isStickerPackInstalled(set.set.id)) {
            MediaDataController.getInstance(currentAccount).toggleStickerSet(null, set, 2, null, false, false);
        }
    }

    private TLRPC.StickerSetCovered findSet(TLObject response, String shortName) {
        if (!(response instanceof TLRPC.TL_messages_myStickers)) {
            return null;
        }
        TLRPC.TL_messages_myStickers myStickers = (TLRPC.TL_messages_myStickers) response;
        for (int i = 0; i < myStickers.sets.size(); i++) {
            TLRPC.StickerSetCovered set = myStickers.sets.get(i);
            if (set.set != null && shortName.equals(set.set.short_name)) {
                return set;
            }
        }
        return null;
    }

    /** Documents in a custom-emoji set carry their alt on the custom-emoji attribute instead. */
    private static String stickerEmoji(TLRPC.Document document) {
        for (int i = 0; i < document.attributes.size(); i++) {
            TLRPC.DocumentAttribute attr = document.attributes.get(i);
            boolean carriesAlt = attr instanceof TLRPC.TL_documentAttributeSticker
                    || attr instanceof TLRPC.TL_documentAttributeCustomEmoji;
            if (carriesAlt && attr.alt != null) {
                return attr.alt;
            }
        }
        return null;
    }

    private static String prefix(long telegramUserId, boolean emojiSet) {
        return (emojiSet ? EMOJI_SHORT_NAME_PREFIX : SHORT_NAME_PREFIX) + telegramUserId + "_";
    }

    private static String shortName(long telegramUserId, long avatarId, boolean emojiSet) {
        return prefix(telegramUserId, emojiSet) + avatarId;
    }

    private static String prefKey(boolean emojiSet) {
        return emojiSet ? PREF_EMOJI_SET_SHORT_NAME : PREF_SET_SHORT_NAME;
    }

    /**
     * Canonical emotion order of the avatar pack: `{emotionId, emoji}`. The emoji doubles as the
     * sticker's `alt` in Telegram and as the reaction that ends up on the bubble, so the two
     * directions must stay in one table.
     */
    private static final String[][] EMOTIONS = {
            {"happy", "\uD83D\uDE00"},
            {"sad", "\uD83D\uDE14"},
            {"cry", "\uD83D\uDE2D"},
            {"angry", "\uD83D\uDE21"},
            {"surprised", "\uD83D\uDE2E"},
            {"cool", "\uD83D\uDE0E"},
            {"thinking", "\uD83E\uDD14"},
            {"laugh", "\uD83D\uDE02"},
            {"love", "\uD83D\uDE0D"},
            {"wink", "\uD83D\uDE09"},
    };

    public static String emojiForEmotion(String emotionId) {
        for (int i = 0; i < EMOTIONS.length; i++) {
            if (EMOTIONS[i][0].equals(emotionId)) {
                return EMOTIONS[i][1];
            }
        }
        return EMOTIONS[0][1];
    }

    public static String emotionForEmoji(String emoji) {
        for (int i = 0; i < EMOTIONS.length; i++) {
            if (EMOTIONS[i][1].equals(emoji)) {
                return EMOTIONS[i][0];
            }
        }
        return null;
    }

    /** Position in the canonical order, or {@code EMOTIONS.length} for anything unrecognised. */
    public static int emotionOrder(String emotionId) {
        for (int i = 0; i < EMOTIONS.length; i++) {
            if (EMOTIONS[i][0].equals(emotionId)) {
                return i;
            }
        }
        return EMOTIONS.length;
    }

    private void rememberSyncedSet(TLRPC.TL_messages_stickerSet set, boolean emojiSet) {
        if (set == null || set.set == null || set.set.short_name == null) {
            return;
        }
        MessagesController.getMainSettings(currentAccount)
                .edit()
                .putString(prefKey(emojiSet), set.set.short_name)
                .apply();
    }

    /**
     * Resolves the avatar pack that backs the in-chat reaction picker. Sets are created by the
     * avatar module's pack sync, so this only ever looks one up.
     *
     * The custom-emoji set wins when it exists, because it is the one that can put the avatar
     * artwork itself onto the bubble; the plain sticker pack is the fallback, and a caller tells
     * the two apart by {@code set.set.emojis}. Reports {@code null} when neither exists yet.
     */
    public static void resolveReactionStickerSet(
            int currentAccount,
            Utilities.Callback<TLRPC.TL_messages_stickerSet> done
    ) {
        long telegramUserId = UserConfig.getInstance(currentAccount).getClientUserId();
        if (telegramUserId == 0) {
            done.run(null);
            return;
        }
        resolveVariant(currentAccount, telegramUserId, true, emojiSet -> {
            if (emojiSet != null) {
                done.run(emojiSet);
            } else {
                resolveVariant(currentAccount, telegramUserId, false, done);
            }
        });
    }

    private static void resolveVariant(
            int currentAccount,
            long telegramUserId,
            boolean emojiSet,
            Utilities.Callback<TLRPC.TL_messages_stickerSet> done
    ) {
        String remembered = MessagesController.getMainSettings(currentAccount)
                .getString(prefKey(emojiSet), null);
        if (remembered != null) {
            loadSetByShortName(currentAccount, remembered, set -> {
                if (set != null) {
                    done.run(set);
                } else {
                    scanMyStickerSets(currentAccount, prefix(telegramUserId, emojiSet), emojiSet, 0, done);
                }
            });
            return;
        }
        scanMyStickerSets(currentAccount, prefix(telegramUserId, emojiSet), emojiSet, 0, done);
    }

    /**
     * Which avatar emotion a custom-emoji document stands for. This is what lets the chat recognise
     * an avatar reaction picked from Telegram's own reaction panel, so that it can be routed through
     * the avatar service instead of going straight to Telegram and bypassing both the throttle and
     * `POST /me/reactions`.
     */
    private static final HashMap<Long, String> REACTION_EMOTIONS = new HashMap<>();
    private static boolean reactionEmotionsLoaded;
    private static boolean reactionEmotionsLoading;

    public static void preloadReactionEmotions(int currentAccount) {
        if (reactionEmotionsLoaded || reactionEmotionsLoading) {
            return;
        }
        reactionEmotionsLoading = true;
        resolveReactionStickerSet(currentAccount, set -> {
            reactionEmotionsLoading = false;
            // A plain sticker pack has no document a reaction could refer to, so there is nothing
            // to recognise and the lookup must stay retryable until an emoji set exists.
            if (set == null || set.set == null || !set.set.emojis) {
                return;
            }
            REACTION_EMOTIONS.clear();
            for (int i = 0; i < set.documents.size(); i++) {
                TLRPC.Document document = set.documents.get(i);
                String emoji = stickerEmoji(document);
                String emotionId = emoji == null ? null : emotionForEmoji(emoji);
                if (emotionId != null) {
                    REACTION_EMOTIONS.put(document.id, emotionId);
                }
            }
            reactionEmotionsLoaded = !REACTION_EMOTIONS.isEmpty();
        });
    }

    /** {@code null} for anything that is not an avatar reaction. */
    public static String emotionForReactionDocument(long documentId) {
        return REACTION_EMOTIONS.get(documentId);
    }

    private static void loadSetByShortName(
            int currentAccount,
            String shortName,
            Utilities.Callback<TLRPC.TL_messages_stickerSet> done
    ) {
        TLRPC.TL_inputStickerSetShortName input = new TLRPC.TL_inputStickerSetShortName();
        input.short_name = shortName;
        MediaDataController.getInstance(currentAccount)
                .getStickerSet(input, null, false, set -> done.run(set));
    }

    private static void scanMyStickerSets(
            int currentAccount,
            String shortNamePrefix,
            boolean emojiSet,
            long offsetId,
            Utilities.Callback<TLRPC.TL_messages_stickerSet> done
    ) {
        TLRPC.TL_messages_getMyStickers getMyStickers = new TLRPC.TL_messages_getMyStickers();
        getMyStickers.offset_id = offsetId;
        getMyStickers.limit = 100;
        ConnectionsManager.getInstance(currentAccount).sendRequest(getMyStickers, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (!(response instanceof TLRPC.TL_messages_myStickers)) {
                if (error != null) {
                    FileLog.w(TAG + ": getMyStickers failed " + error.text);
                }
                done.run(null);
                return;
            }
            TLRPC.TL_messages_myStickers myStickers = (TLRPC.TL_messages_myStickers) response;
            for (int i = 0; i < myStickers.sets.size(); i++) {
                TLRPC.StickerSetCovered covered = myStickers.sets.get(i);
                if (covered.set == null || covered.set.short_name == null) {
                    continue;
                }
                if (covered.set.short_name.startsWith(shortNamePrefix)) {
                    String shortName = covered.set.short_name;
                    MessagesController.getMainSettings(currentAccount)
                            .edit()
                            .putString(prefKey(emojiSet), shortName)
                            .apply();
                    loadSetByShortName(currentAccount, shortName, done);
                    return;
                }
            }
            if (myStickers.sets.size() >= getMyStickers.limit) {
                TLRPC.StickerSetCovered lastSet = myStickers.sets.get(myStickers.sets.size() - 1);
                if (lastSet.set != null && lastSet.set.id != 0 && lastSet.set.id != offsetId) {
                    scanMyStickerSets(currentAccount, shortNamePrefix, emojiSet, lastSet.set.id, done);
                    return;
                }
            }
            done.run(null);
        }));
    }
}
