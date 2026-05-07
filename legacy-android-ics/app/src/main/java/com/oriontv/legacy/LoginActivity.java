package com.oriontv.legacy;

import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;

import com.oriontv.legacy.api.ApiCallback;
import com.oriontv.legacy.api.models.LoginResult;
import com.oriontv.legacy.ui.Ui;

public class LoginActivity extends BaseActivity {
    private EditText username;
    private EditText password;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = Ui.vertical(this);
        root.addView(Ui.title(this, "登录"));
        username = Ui.edit(this, "用户名，可留空");
        password = Ui.edit(this, "密码，可留空");
        password.setInputType(0x00000081);
        Button login = Ui.button(this, "登录");

        root.addView(username, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 52)));
        root.addView(Ui.spacer(this, 1, 12));
        root.addView(password, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 52)));
        root.addView(Ui.spacer(this, 1, 18));
        root.addView(login, new LinearLayout.LayoutParams(Ui.dp(this, 120), Ui.dp(this, 50)));

        login.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doLogin();
            }
        });
        setContentView(root);
    }

    private void doLogin() {
        app.api().login(username.getText().toString(), password.getText().toString(), new ApiCallback<LoginResult>() {
            @Override
            public void onSuccess(LoginResult value) {
                if (value != null && value.ok) {
                    Ui.toast(LoginActivity.this, "登录成功");
                    finish();
                } else {
                    Ui.toast(LoginActivity.this, "登录失败");
                }
            }

            @Override
            public void onError(Throwable error) {
                handleError(error);
            }
        });
    }
}
