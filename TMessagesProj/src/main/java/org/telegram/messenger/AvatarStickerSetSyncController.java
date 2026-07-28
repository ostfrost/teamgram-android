package org.telegram.messenger;

import com.sousa.feature_avatar.bridge.TelegramStickerPackSyncCallback;
import com.sousa.feature_avatar.bridge.TelegramStickerPackSyncRequest;
import com.sousa.feature_avatar.bridge.TelegramStickerPackUnit;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class AvatarStickerSetSyncController implements TelegramStickerPackSyncCallback {
    private static final String TAG = "AvatarStickerSync";
    private static final String SHORT_NAME_PREFIX = "teamgram_avatar_";
    private static final String SOFTWARE = "Teamgram Avatar";

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
        String shortName = shortName(telegramUserId, request.getAvatarId());
        findMyStickerSet(request, shortName, 0);
    }

    private void findMyStickerSet(TelegramStickerPackSyncRequest request, String shortName, long offsetId) {
        TLRPC.TL_messages_getMyStickers getMyStickers = new TLRPC.TL_messages_getMyStickers();
        getMyStickers.offset_id = offsetId;
        getMyStickers.limit = 100;
        ConnectionsManager.getInstance(currentAccount).sendRequest(getMyStickers, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (error != null) {
                FileLog.w(TAG + ": getMyStickers failed " + error.text);
                return;
            }
            TLRPC.StickerSetCovered covered = findSet(response, shortName);
            if (covered != null) {
                loadStickerSetAndUpdate(request, covered);
                return;
            }
            if (response instanceof TLRPC.TL_messages_myStickers) {
                TLRPC.TL_messages_myStickers myStickers = (TLRPC.TL_messages_myStickers) response;
                if (myStickers.sets.size() >= getMyStickers.limit) {
                    TLRPC.StickerSetCovered lastSet = myStickers.sets.get(myStickers.sets.size() - 1);
                    if (lastSet.set != null && lastSet.set.id != 0 && lastSet.set.id != offsetId) {
                        findMyStickerSet(request, shortName, lastSet.set.id);
                        return;
                    }
                }
            }
            createStickerSet(request, shortName);
        }));
    }
    private void createStickerSet(TelegramStickerPackSyncRequest request, String shortName) {
        uploadUnits(request.getUnits(), new ArrayList<>(), inputItems -> {
            if (inputItems.size() != request.getUnits().size()) {
                FileLog.w(TAG + ": create skipped, uploaded " + inputItems.size() + " of " + request.getUnits().size());
                return;
            }
            TLRPC.TL_stickers_createStickerSet create = new TLRPC.TL_stickers_createStickerSet();
            create.user_id = new TLRPC.TL_inputUserSelf();
            create.title = request.getTitle();
            create.short_name = shortName;
            create.software = SOFTWARE;
            create.flags |= 8;
            create.stickers.addAll(inputItems);
            ConnectionsManager.getInstance(currentAccount).sendRequest(create, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                if (response instanceof TLRPC.TL_messages_stickerSet) {
                    TLRPC.TL_messages_stickerSet set = (TLRPC.TL_messages_stickerSet) response;
                    MediaDataController.getInstance(currentAccount).putStickerSet(set);
                    installIfNeeded(set);
                    FileLog.d(TAG + ": created " + shortName);
                } else if (error != null) {
                    FileLog.w(TAG + ": create failed " + error.text);
                }
            }));
        });
    }

    private void loadStickerSetAndUpdate(TelegramStickerPackSyncRequest request, TLRPC.StickerSetCovered covered) {
        TLRPC.TL_messages_getStickerSet getStickerSet = new TLRPC.TL_messages_getStickerSet();
        getStickerSet.stickerset = MediaDataController.getInputStickerSet(covered.set);
        getStickerSet.hash = 0;
        ConnectionsManager.getInstance(currentAccount).sendRequest(getStickerSet, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (response instanceof TLRPC.TL_messages_stickerSet) {
                updateStickerSet(request, (TLRPC.TL_messages_stickerSet) response);
            } else if (error != null) {
                FileLog.w(TAG + ": getStickerSet failed " + error.text);
            }
        }));
    }

    private void updateStickerSet(TelegramStickerPackSyncRequest request, TLRPC.TL_messages_stickerSet set) {
        MediaDataController.getInstance(currentAccount).putStickerSet(set);
        HashMap<String, TLRPC.Document> existingByEmoji = new HashMap<>();
        for (int i = 0; i < set.documents.size(); i++) {
            TLRPC.Document document = set.documents.get(i);
            String emoji = stickerEmoji(document);
            if (emoji != null) {
                existingByEmoji.put(emoji, document);
            }
        }
        updateUnitAt(request.getUnits(), existingByEmoji, 0, set);
    }

    private void updateUnitAt(List<TelegramStickerPackUnit> units, HashMap<String, TLRPC.Document> existingByEmoji, int index, TLRPC.TL_messages_stickerSet set) {
        if (index >= units.size()) {
            installIfNeeded(set);
            FileLog.d(TAG + ": updated " + set.set.short_name);
            return;
        }
        TelegramStickerPackUnit unit = units.get(index);
        uploadUnit(unit, inputItem -> {
            if (inputItem == null) {
                updateUnitAt(units, existingByEmoji, index + 1, set);
                return;
            }
            TLRPC.Document oldDocument = existingByEmoji.get(inputItem.emoji);
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
                    updateUnitAt(units, existingByEmoji, index + 1, updatedSet);
                } else {
                    if (error != null) {
                        FileLog.w(TAG + ": update unit " + unit.getEmotionId() + " failed " + error.text);
                    }
                    updateUnitAt(units, existingByEmoji, index + 1, set);
                }
            }));
        });
    }

    private void uploadUnits(List<TelegramStickerPackUnit> units, ArrayList<TLRPC.TL_inputStickerSetItem> uploaded, Utilities.Callback<ArrayList<TLRPC.TL_inputStickerSetItem>> done) {
        if (uploaded.size() >= units.size()) {
            done.run(uploaded);
            return;
        }
        TelegramStickerPackUnit unit = units.get(uploaded.size());
        uploadUnit(unit, item -> {
            if (item != null) {
                uploaded.add(item);
            }
            uploadUnits(units, uploaded, done);
        });
    }

    private void uploadUnit(TelegramStickerPackUnit unit, Utilities.Callback<TLRPC.TL_inputStickerSetItem> done) {
        File file = new File(unit.getFilePath());
        if (!file.isFile() || file.length() == 0) {
            FileLog.w(TAG + ": missing file for " + unit.getEmotionId() + " path=" + unit.getFilePath());
            done.run(null);
            return;
        }
        FileLoader.getInstance(currentAccount).uploadFile(unit.getFilePath(), inputFile -> {
            if (inputFile == null) {
                FileLog.w(TAG + ": uploadFile failed for " + unit.getEmotionId());
                done.run(null);
                return;
            }
            uploadMedia(unit, inputFile, done);
        });
    }

    private void uploadMedia(TelegramStickerPackUnit unit, TLRPC.InputFile inputFile, Utilities.Callback<TLRPC.TL_inputStickerSetItem> done) {
        TLRPC.TL_messages_uploadMedia uploadMedia = new TLRPC.TL_messages_uploadMedia();
        uploadMedia.peer = new TLRPC.TL_inputPeerSelf();
        TLRPC.TL_inputMediaUploadedDocument media = new TLRPC.TL_inputMediaUploadedDocument();
        media.file = inputFile;
        media.mime_type = unit.getMimeType();
        if ("video/webm".equals(unit.getMimeType())) {
            media.nosound_video = true;
            TLRPC.TL_documentAttributeVideo videoAttr = new TLRPC.TL_documentAttributeVideo();
            videoAttr.nosound = true;
            videoAttr.duration = 1.0;
            videoAttr.w = 512;
            videoAttr.h = 512;
            media.attributes.add(videoAttr);
        }
        TLRPC.TL_documentAttributeSticker attr = new TLRPC.TL_documentAttributeSticker();
        attr.alt = emojiForEmotion(unit.getEmotionId());
        attr.stickerset = new TLRPC.TL_inputStickerSetEmpty();
        media.attributes.add(attr);
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

    private String stickerEmoji(TLRPC.Document document) {
        for (int i = 0; i < document.attributes.size(); i++) {
            TLRPC.DocumentAttribute attr = document.attributes.get(i);
            if (attr instanceof TLRPC.TL_documentAttributeSticker && attr.alt != null) {
                return attr.alt;
            }
        }
        return null;
    }

    private static String shortName(long telegramUserId, long avatarId) {
        return SHORT_NAME_PREFIX + telegramUserId + "_" + avatarId;
    }

    private static String emojiForEmotion(String emotionId) {
        if ("happy".equals(emotionId)) return "\uD83D\uDE00";
        if ("sad".equals(emotionId)) return "\uD83D\uDE14";
        if ("cry".equals(emotionId)) return "\uD83D\uDE22";
        if ("angry".equals(emotionId)) return "\uD83D\uDE21";
        if ("surprised".equals(emotionId)) return "\uD83D\uDE2E";
        if ("cool".equals(emotionId)) return "\uD83D\uDE0E";
        if ("thinking".equals(emotionId)) return "\uD83E\uDD14";
        if ("laugh".equals(emotionId)) return "\uD83D\uDE02";
        if ("love".equals(emotionId)) return "\uD83D\uDE0D";
        if ("wink".equals(emotionId)) return "\uD83D\uDE09";
        return "\uD83D\uDE00";
    }
}
