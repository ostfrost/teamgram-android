package org.telegram.ui;

import android.content.res.Configuration;
import android.content.Context;
import android.content.Intent;
import android.view.View;

import com.sousa.feature_avatar.bridge.AvatarExtension;
import com.sousa.feature_avatar.bridge.TelegramAvatarReactionTarget;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.AvatarStickerSetSyncController;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.BaseFragment;

import kotlin.Unit;

public class AvatarBridgeActivity extends BaseFragment {
    private AvatarHostIntegrationController hostIntegration;
    private final TelegramAvatarReactionTarget reactionTarget;

    public AvatarBridgeActivity() {
        this(null);
    }

    public AvatarBridgeActivity(TelegramAvatarReactionTarget reactionTarget) {
        this.reactionTarget = reactionTarget;
    }

    /**
     * Opens the avatar feature bound to a chat message, so the emotion tab sends reactions into
     * that conversation instead of only previewing the pack. Without a target the feature behaves
     * exactly as it does when opened from settings.
     */
    public static AvatarBridgeActivity forReaction(long dialogId, int messageId) {
        return new AvatarBridgeActivity(
                new TelegramAvatarReactionTarget(dialogId, "message", String.valueOf(messageId))
        );
    }

    @Override
    public View createView(Context context) {
        actionBar.setAddToContainer(false);
        AvatarStickerSetSyncController stickerController =
                new AvatarStickerSetSyncController(UserConfig.selectedAccount);
        hostIntegration = new AvatarHostIntegrationController(
                this,
                UserConfig.selectedAccount
        );

        fragmentView = AvatarExtension.INSTANCE.createWithHostCallbacks(context, () -> {
            finishFragment();
            return Unit.INSTANCE;
        }, stickerController, hostIntegration, reactionTarget);

        return fragmentView;
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (hostIntegration == null || !hostIntegration.onActivityResult(requestCode, resultCode, data)) {
            super.onActivityResultFragment(requestCode, resultCode, data);
        }
    }

    @Override
    public void onFragmentDestroy() {
        if (hostIntegration != null) {
            hostIntegration.clear();
            hostIntegration = null;
        }
        super.onFragmentDestroy();
    }

    @Override
    public boolean isLightStatusBar() {
        Context context = getContext();
        if (context == null) {
            context = ApplicationLoader.applicationContext;
        }
        int currentNightMode = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return currentNightMode != Configuration.UI_MODE_NIGHT_YES;
    }
}
