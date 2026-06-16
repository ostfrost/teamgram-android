package org.telegram.ui;

import android.content.Context;
import android.view.View;

import com.sousa.feature_avatar.bridge.AvatarExtension;

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
        return false;
    }
}
