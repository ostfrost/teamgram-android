package org.telegram.ui;

import android.content.res.Configuration;
import android.content.Context;
import android.view.View;

import com.sousa.feature_avatar.bridge.AvatarExtension;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.ui.ActionBar.BaseFragment;

import kotlin.Unit;

public class AvatarBridgeActivity extends BaseFragment {

    @Override
    public View createView(Context context) {
        actionBar.setAddToContainer(false);

        fragmentView = AvatarExtension.INSTANCE.create(context, () -> {
            finishFragment();
            return Unit.INSTANCE;
        });

        return fragmentView;
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
