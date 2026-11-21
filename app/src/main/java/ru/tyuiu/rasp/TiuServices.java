package ru.tyuiu.rasp;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

final class TiuServices {
    static final String MY_TIU = "https://my.tyuiu.ru/";
    static final String EDUCON = "https://educon2.tyuiu.ru";
    static final String EDUCON_GRADES = EDUCON + "/grade/report/overview/index.php";

    private TiuServices() {}

    static void openTab(Activity owner, String url, ThemePalette palette) {
        var extras = new Bundle();
        extras.putBinder("android.support.customtabs.extra.SESSION", null);
        var tab = new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
                .putExtras(extras).putExtra("android.support.customtabs.extra.TOOLBAR_COLOR", palette.card);
        try {
            owner.startActivity(tab);
        } catch (ActivityNotFoundException unavailable) {
            Toast.makeText(owner, "Браузер не найден", Toast.LENGTH_LONG).show();
        }
    }
}
