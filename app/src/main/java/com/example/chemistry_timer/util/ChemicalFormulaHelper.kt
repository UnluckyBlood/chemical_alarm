package com.example.chemistry_timer.util

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.SubscriptSpan
import android.text.style.SuperscriptSpan

object ChemicalFormulaHelper {

    fun parseFormula(input: String): SpannableStringBuilder {
        val builder = SpannableStringBuilder()
        var i = 0
        while (i < input.length) {
            when (input[i]) {
                '_' -> {
                    i++
                    val sub = readIndex(input, i)
                    val start = builder.length
                    builder.append(sub)
                    builder.setSpan(SubscriptSpan(), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    builder.setSpan(RelativeSizeSpan(0.7f), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    i += sub.length
                }
                '^' -> {
                    i++
                    val sup = readIndex(input, i)
                    val start = builder.length
                    builder.append(sup)
                    builder.setSpan(SuperscriptSpan(), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    builder.setSpan(RelativeSizeSpan(0.7f), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    i += sup.length
                }
                else -> {
                    builder.append(input[i])
                    i++
                }
            }
        }
        return builder
    }

    private fun readIndex(input: String, start: Int): String {
        val sb = StringBuilder()
        var i = start
        if (i < input.length && input[i] == '(') {
            while (i < input.length) {
                sb.append(input[i])
                if (input[i] == ')') { i++; break }
                i++
            }
        } else {
            while (i < input.length && (input[i].isDigit() || input[i] == '+' || input[i] == '-')) {
                sb.append(input[i])
                i++
            }
        }
        return sb.toString()
    }
}