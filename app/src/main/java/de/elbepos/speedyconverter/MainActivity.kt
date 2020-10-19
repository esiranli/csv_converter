package de.elbepos.speedyconverter

import android.app.ProgressDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.opencsv.CSVWriter
import com.opencsv.bean.CsvBindByName
import kotlinx.android.synthetic.main.activity_main.*
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.NumberFormat
import java.util.*
import kotlin.collections.ArrayList
import kotlin.math.pow

class MainActivity : AppCompatActivity() {

    companion object {
        private val SELECT_CSV_FILE_REQUEST_CODE = 1008
        private val PERMISSIONS = arrayOf(
            android.Manifest.permission.READ_EXTERNAL_STORAGE,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
        private val PERMISSIONS_REQUEST_CODE = 1009
        private val ARTIKEL_HEADER = arrayOf(
            "ArtikelID",
            "ArtikelTyp",
            "Artikelnummer",
            "ArtikelTextKurz",
            "ArtikelTextLang",
            "Bemerkung",
            "ArtikelGruppenID",
            "SortierIndex",
            "Mengeneinheit",
            "MengeAnzahlNachkommastellen",
            "Einkaufspreis",
            "Verkaufspreis",
            "RabattID",
            "Steuersatz",
            "Pfandpreis",
            "BonDruckJaNein",
            "PfandBonDruckJaNein",
            "Status",
            "Buchungsverhalten",
            "Variantentexte",
            "VerkaufspreisÄnderbar",
            "ArtikeltextÄnderbar",
            "NotizÄnderbar",
            "Bewertung",
            "LagerortID",
            "Artikelbild",
            "Kassenzuordnung"
        )
        private val ARTIKEL_GRUPPEN_HEADER = arrayOf(
            "ArtikelGruppenID",
            "ArtikelGruppenText",
            "Hintergrundfarbe",
            "Textfarbe",
            "Status",
            "SortierIndex",
            "RabattID",
            "VaterArtikelGruppenID",
            "Anzeigemodus",
            "Kassenzuordnung"
        )
        private val ARTIKEL_CSV_FILE_NAME = "Artikel.csv"
        private val ARTIKEL_GRUPPEN_CSV_FILE_NAME = "ArtikelGruppen.csv"
    }

    private var sourceItems = ArrayList<SourceItem>()
    private var file: File? = null
    private var progressDialog: ProgressDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        requestRuntimePermissions()

        button.setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT)
            intent.addCategory(Intent.CATEGORY_OPENABLE)
            intent.type = "*/*"
            startActivityForResult(
                Intent.createChooser(intent, "Open CSV"),
                SELECT_CSV_FILE_REQUEST_CODE
            )
        }
    }

    private fun requestRuntimePermissions() {
        val requiredPermissions = PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_DENIED
        }
        if (requiredPermissions.isEmpty()) return

        ActivityCompat.requestPermissions(
            this,
            requiredPermissions.toTypedArray(),
            PERMISSIONS_REQUEST_CODE
        );
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == PERMISSIONS_REQUEST_CODE && grantResults.isNotEmpty()) {
            val requiredPermissions = mutableListOf<String>()
            grantResults.forEachIndexed { index, i ->
                if (i != PackageManager.PERMISSION_GRANTED) {
                    requiredPermissions.add(permissions[index])
                }
            }
            if (requiredPermissions.isEmpty()) {
                return
            }

            for (permission in requiredPermissions) {
                if (ActivityCompat.shouldShowRequestPermissionRationale(this, permission)) {
                    showPermissionRequiredAlert()
                } else {
                    showGotoSettingsAlert()
                    break
                }
            }
        }
    }

    private fun showGotoSettingsAlert() {
        AlertDialog.Builder(this).apply {
            setTitle("")
            setMessage(R.string.allow_all_permissions_at_settings_title)
            setPositiveButton(R.string.go_to_settings_title) { dialog, _ ->
                dialog.dismiss()
                goToSettings()
                finish()
            }
            setNegativeButton(R.string.cancel_title, null)
        }.create().show()
    }

    private fun showPermissionRequiredAlert() {
        AlertDialog.Builder(this).apply {
            title = ""
            setMessage(R.string.permission_required_title)
            setPositiveButton(R.string.ok_title) { dialog, _ ->
                dialog.dismiss()
                requestRuntimePermissions()
            }
            setNegativeButton(R.string.cancel_title, null)
        }
    }

    private fun goToSettings() {
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts(
                "package",
                packageName,
                null
            )
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(this)
        }
    }


    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            SELECT_CSV_FILE_REQUEST_CODE -> {
                if (resultCode == RESULT_OK) {
                    if (data?.data != null) {
                        file = de.elbepos.speedyconverter.FileUtils.getFile(this, data.data)
                        fileTextView.text = file?.name

                        showLoading()
                        if (readData()) {
                            writeCategoryCSV()
                            writeItemCSV()
                        }
                        hideLoading()
                        Toast.makeText(
                            this,
                            "CSV files successfully created under csv folder at internal storage",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        fileTextView.text = getString(R.string.no_file_selected_title)
                    }
                }
            }
        }
    }

    private fun writeCategoryCSV() {
        val lines = mutableListOf<Array<String>>(ARTIKEL_GRUPPEN_HEADER)
        getTargetCategories().forEach { lines.add(it.toLine()) }
        writeCSV(ARTIKEL_GRUPPEN_CSV_FILE_NAME, lines)
    }

    private fun writeItemCSV() {
        val lines = mutableListOf<Array<String>>(ARTIKEL_HEADER)
        getTargetItems().forEach { lines.add(it.toLine()) }
        writeCSV(ARTIKEL_CSV_FILE_NAME, lines)
    }

    private fun writeCSV(filename: String, lines: List<Array<String>>): Boolean {
        var writer: CSVWriter? = null
        try {
            val folder =
                File(Environment.getExternalStorageDirectory().toString() + "/csv")
            folder.mkdirs()
            val extStorageDirectory = folder.toString()
            val file = File(extStorageDirectory, filename)
            if (file.exists()) {
                file.createNewFile()
            }
            val fileWriter = FileWriter(file, false)
            writer = CSVWriter(
                fileWriter,
                ';',
                CSVWriter.NO_QUOTE_CHARACTER,
                CSVWriter.DEFAULT_ESCAPE_CHARACTER,
                CSVWriter.DEFAULT_LINE_END
            )
            writer.writeAll(lines)
        } catch (e: java.lang.Exception) {
            hideLoading()
            Log.e("MainActivity", e.message)
            return false
        } finally {
            writer?.close()
        }
        return true
    }


    private fun readData(): Boolean {
        try {
//            val reader = resources.openRawResource(R.raw.test).reader()
            if (file != null) {
                sourceItems.clear()
                val reader = FileReader(file).buffered()
                val iterator = reader.lineSequence().iterator()
                while(iterator.hasNext()) {
                    val line = iterator.next()
                    // do something with line...
                    val item = lineToData(line)
                    sourceItems.add(item)
                }
                reader.close()
                Log.d("MainActivity", "Success: ${sourceItems.size} items read!!")
                return true
            } else {
                hideLoading()
                return false
            }
        } catch (e: Exception) {
            hideLoading()
            Toast.makeText(this, e.message, Toast.LENGTH_SHORT).show()
            return false
        }
    }

    private fun getTargetCategories(): List<TargetCategory> {
        val categoryList = sourceItems.map { it.category }.toSet().toList().filterNot { it.isEmpty() || it == "category" }
        //        targetCategories.removeAt(0)
        return categoryList.mapIndexed { index, s ->
            TargetCategory(
                id = (index + 1).toString(),
                name = s,
                sortIndex = (index + 1) * 10
            )
        }.toMutableList()
    }

    private fun getTargetItems(): List<TargetItem> {
        val targetCategories = getTargetCategories()
        val targetItems = sourceItems.mapIndexed { index, sourceItem ->
            val categoryId = targetCategories.find { it.name == sourceItem.category }?.id ?: ""
            TargetItem(
                id = (index).toString(),
                number = sourceItem.gtin,
                name = sourceItem.name.replace(",", "."),
                categoryId = categoryId,
                sortIndex = index * 10,
                salePrice = sourceItem.price_per_unit ?: 0.00,
                vat = sourceItem.getTaxPercentage(),
                depositPrice = sourceItem.getDepositPrice()
            )
        }.toMutableList()
        targetItems.removeAt(0)
        return targetItems
    }



    private fun lineToData(line: String): SourceItem {
        val tokens = line.split(";")
        return SourceItem(
            type = tokens[0],
            price_per_unit = tokens[1].toDoubleOrNull(),
            gtin = tokens[2],
            name = tokens[3],
            quantity = tokens[4].toDoubleOrNull(),
            quantity_factor = tokens[5].toDoubleOrNull(),
            quantity_measure = tokens[6],
            category = tokens[7],
            vat = tokens[8],
            in_haus_vat_id = tokens[9].toIntOrNull(),
            ausser_haus_vat_id = tokens[10].toIntOrNull(),
            pfand_type = tokens[11],
            pfand_bruttopreis = tokens[12].toDoubleOrNull()
        )
    }

    private fun showLoading() {
        progressDialog = ProgressDialog.show(this, "", "Creating import csv files")
    }

    private fun hideLoading() {
        progressDialog?.dismiss()
    }
}

data class SourceItem(
    @CsvBindByName val type: String,
    @CsvBindByName val price_per_unit: Double?,
    @CsvBindByName val gtin: String,
    @CsvBindByName val name: String,
    @CsvBindByName val quantity: Double?,
    @CsvBindByName val quantity_factor: Double?,
    @CsvBindByName val quantity_measure: String,
    @CsvBindByName val category: String,
    @CsvBindByName val vat: String,
    @CsvBindByName val in_haus_vat_id: Int?,
    @CsvBindByName val ausser_haus_vat_id: Int?,
    @CsvBindByName val pfand_type: String?,
    @CsvBindByName val pfand_bruttopreis: Double?
) {
    override fun toString(): String {
        return "$gtin\t$name\t$price_per_unit"
    }

    fun getTaxPercentage(): String {
        return when (vat) {
            "TABAKWAREN", "Getränke", "16", "19" -> "16"
            "5", "7" -> "5"
            else -> "0"
        }
    }

//    {
//        "id": "5e0cc7151230b39c8d88c74a",
//        "type": "Mehrweg-Bierflasche aus Glas",
//        "bruttopreis": 0.08
//    },
//    {
//        "id": "5e0cc7151230b39c8d88c74b",
//        "type": "Mehrweg-Bierflasche mit Bügelverschluss",
//        "bruttopreis": 0.15
//    },
//    {
//        "id": "5e0cc7151230b39c8d88c74c",
//        "type": "Mehrwegflaschen für Saft oder Softdrinks",
//        "bruttopreis": 0.15
//    },
//    {
//        "id": "5e0cc7151230b39c8d88c74d",
//        "type": "Einwegflaschen und -dosen",
//        "bruttopreis": 0.25
//    }
    fun getDepositPrice(): Double? {
        if (pfand_type == null) return null
        return when (pfand_type) {
            "Mehrweg-Bierflasche aus Glas" -> 0.08
            "Mehrweg-Bierflasche mit Bügelverschluss" -> 0.15
            "Mehrwegflaschen für Saft oder Softdrinks" -> 0.15
            "Einwegflaschen und -dosen" -> 0.25
            else -> null
        }
    }
}

data class TargetItem(
    val id: String,
    val itemType: Int = 0,
    val number: String,
    val shortName: String,
    val longName: String,
    val comment: String = "",
    val categoryId: String,
    val sortIndex: Int,
    val quantityMeasure: String = "Stk",
    val quantityNumberAfterDecimalPoint: Int = 0,
    val unitPrice: Double = 0.00,
    val salePrice: Double,
    val discountId: String = "",
    val vat: String,
    val depositPrice: Double = 0.00,
    val belegReceipt: String = "nein",
    val depositReceipt: String = "nein",
    val status: Int = 0,
    val bookingBehaviour: Int = 0,
    val variantText: String = "",
    val salePriceChangeable: String = "ja",
    val itemTextChangeable: String = "ja",
    val noteChangeable: String = "ja",
    val rating: Int = 0,
    val storeLocationId: String = "",
    val itemImage: String = "",
    val cashOrder: String = ""
) {
    constructor(
        id: String,
        number: String,
        name: String,
        categoryId: String,
        sortIndex: Int,
        salePrice: Double,
        vat: String,
        depositPrice: Double?
    ): this(
        id = id,
        number = number,
        shortName = name,
        longName = name,
        categoryId = categoryId,
        sortIndex = sortIndex,
        salePrice = salePrice,
        vat = vat,
        depositPrice = depositPrice ?: 0.00
    )

    fun toLine(): Array<String> {
        return arrayOf(
            id,
            itemType.toString(),
            number,
            shortName,
            longName,
            comment,
            categoryId,
            sortIndex.toString(),
            quantityMeasure,
            quantityNumberAfterDecimalPoint.toString(),
            unitPrice.toFormatted(),
            salePrice.toFormatted(),
            discountId,
            vat,
            depositPrice.toFormatted(),
            belegReceipt,
            depositReceipt,
            status.toString(),
            bookingBehaviour.toString(),
            variantText,
            salePriceChangeable,
            itemTextChangeable,
            noteChangeable,
            rating.toString(),
            storeLocationId,
            itemImage,
            cashOrder
        )
    }

    override fun toString(): String {
        return arrayOf(
            id, itemType.toString(), number, shortName, longName, comment,
            sortIndex.toString(), quantityMeasure, quantityNumberAfterDecimalPoint.toString(),
            unitPrice.toFormatted(), salePrice.toFormatted(), discountId, vat, belegReceipt,
            depositReceipt, status.toString(), bookingBehaviour.toString(), variantText,
            salePriceChangeable, itemTextChangeable, noteChangeable, rating.toString(),
            storeLocationId, itemImage, cashOrder
        ).joinToString(",")
    }
}

data class TargetCategory(
    val id: String,
    val name: String,
    val backgroundColor: String,
    val textColor: String,
    val status: Int,
    val sortIndex: Int,
    val discountId: String,
    val parentCategoryId: Int,
    val displayMode: Int,
    val cashOrder: String
) {
    constructor(id: String, name: String, sortIndex: Int): this(
        id = id,
        name = name,
        backgroundColor = "#ff333333",
        textColor = "#ffffffff",
        status = 0,
        sortIndex = sortIndex,
        discountId = "",
        parentCategoryId = -1,
        displayMode = 0,
        cashOrder = ""
    )

    fun toLine(): Array<String> {
        return arrayOf(
            id, name, backgroundColor, textColor, status.toString(),
            sortIndex.toString(), discountId, parentCategoryId.toString(),
            displayMode.toString(), cashOrder
        )
    }
}

fun Double.toFormatted(): String {
    val numberFormat = NumberFormat.getInstance()
    numberFormat.minimumFractionDigits = 2
    numberFormat.maximumFractionDigits = 2
    val currency = Currency.getInstance("EUR")
    numberFormat.currency = currency
    return numberFormat.format(this)
}

fun Double.round(places: Int): Double {
    if (places < 0) throw IllegalArgumentException()

    return BigDecimal.valueOf(this)
        .setScale(places, RoundingMode.HALF_UP)
        .toDouble()

//    val factor = Math.pow(10.0, places.toDouble())
//    var value = this
//    value *= factor
//    val tmp = Math.round(value).toDouble();
//    return tmp / factor;
}