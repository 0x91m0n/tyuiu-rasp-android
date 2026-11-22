package ru.tyuiu.rasp;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

final class TiuServices {
    static final String MY_TIU = "https://my.tyuiu.ru/";
    static final String EDUCON = "https://educon2.tyuiu.ru/";

    private TiuServices() {}

    static void openBrowser(Activity owner, String url) {
        var link = new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE);
        try {
            owner.startActivity(new Intent(link).setPackage("com.android.chrome"));
            return;
        } catch (ActivityNotFoundException unavailable) {
            // Use the system browser when Chrome is unavailable.
        }
        try {
            owner.startActivity(link);
        } catch (ActivityNotFoundException unavailable) {
            Toast.makeText(owner, "Браузер не найден", Toast.LENGTH_LONG).show();
        }
    }
}
