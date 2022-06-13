

package com.google.ar.core.examples.java.persistentcloudanchor;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.fragment.app.DialogFragment;


/** A DialogFragment for the Privacy Notice Dialog Box. */
public class PrivacyNoticeDialogFragment extends DialogFragment {

  /** Listener for weather to start a host or resolve operation. */
  public interface HostResolveListener {

    /** Invoked when the user accepts sharing experience. */
    void onPrivacyNoticeReceived();
  }

  HostResolveListener hostResolveListener;

  static PrivacyNoticeDialogFragment createDialog(HostResolveListener hostResolveListener) {
    PrivacyNoticeDialogFragment dialogFragment = new PrivacyNoticeDialogFragment();
    dialogFragment.hostResolveListener = hostResolveListener;
    return dialogFragment;
  }

  @Override
  public void onDetach() {
    super.onDetach();
    hostResolveListener = null;
  }

  @NonNull
  @Override
  public Dialog onCreateDialog(Bundle savedInstanceState) {
    AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
    builder
        .setTitle(R.string.share_experience_title)
        .setMessage(R.string.share_experience_message)
        .setPositiveButton(
            R.string.agree_to_share,
                (dialog, id) -> {
                  // Send the positive button event back to the host activity
                  hostResolveListener.onPrivacyNoticeReceived();
                })
        .setNegativeButton(
            R.string.learn_more,
                (dialog, id) -> requireActivity().startActivity(
                        new Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.learn_more_url))
                        )));
    return builder.create();
  }
}
