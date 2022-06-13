

package com.google.ar.core.examples.java.persistentcloudanchor;

import android.content.Intent;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.button.MaterialButton;
import com.google.ar.core.examples.java.common.helpers.DisplayRotationHelper;

/** Main Navigation Activity for the Persistent Cloud Anchor Sample. */
public class MainLobbyActivity extends AppCompatActivity {

  private DisplayRotationHelper displayRotationHelper;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.main_lobby);
    displayRotationHelper = new DisplayRotationHelper(this);
    MaterialButton hostButton = findViewById(R.id.host_button);
    hostButton.setOnClickListener((view) -> startActivity(new Intent(this, CloudAnchorActivity.class)));
  }

  @Override
  protected void onResume() {
    super.onResume();
    displayRotationHelper.onResume();
  }

  @Override
  public void onPause() {
    super.onPause();
    displayRotationHelper.onPause();
  }
}
