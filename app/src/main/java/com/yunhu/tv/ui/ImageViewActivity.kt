package com.yunhu.tv.ui

import android.os.Bundle
import android.view.KeyEvent
import androidx.appcompat.app.AppCompatActivity
import coil.load
import com.yunhu.tv.R
import com.yunhu.tv.databinding.ActivityImageViewBinding

/** 全屏看图：确认键或返回键退出 */
class ImageViewActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "url"
    }

    private lateinit var binding: ActivityImageViewBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityImageViewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        binding.ivFull.load(url) { placeholder(R.drawable.bg_card); error(R.drawable.bg_card) }
        binding.ivFull.setOnClickListener { finish() }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
