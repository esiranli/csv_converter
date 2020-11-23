package de.elbepos.speedyconverter

import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.os.HandlerCompat
import com.opencsv.CSVWriter
import com.opencsv.bean.CsvBindByName
import kotlinx.android.synthetic.main.activity_main.*
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.collections.ArrayList

class MainActivity : AppCompatActivity() {

    companion object {
        private val SELECT_ELBEPOS_EXPORT_CSV_FILE_REQUEST_CODE = 1008
        private val SELECT_SPEEDY_EXPORT_CSV_FILE_REQUEST_CODE = 1010
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
            "Steuersatz2",
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
    private var targetItems = ArrayList<TargetItem>()
    private var elbeposExportFile: File? = null
    private var speedyExportFile: File? = null
    private var progressDialog: ProgressDialog? = null

    private var imhausOldValue1 = 5
    private var imhausOldValue2 = 16
    private var ausserhausOldValue1 = 5
    private var ausserhausOldValue2 = 16

    private var imhausNewValue1 = 0
    private var imhausNewValue2 = 0
    private var ausserhausNewValue1 = 0
    private var ausserhausNewValue2 = 0
    private var formatter: NumberFormat? = null

    private val executorService: ExecutorService = Executors.newFixedThreadPool(4)
    private val mainThreadHandler: Handler = HandlerCompat.createAsync(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        requestRuntimePermissions()
        formatter = getNumberFormat()

        button_import_csv.setOnClickListener {
            openFile("Open CSV", SELECT_ELBEPOS_EXPORT_CSV_FILE_REQUEST_CODE)
        }
        button_import_csv_speedy.setOnClickListener {
            try {
                ausserhausNewValue1 = editTextNumberAusserhausNewValue1.text.toString().toInt()
                ausserhausNewValue2 = editTextNumberAusserhausNewValue2.text.toString().toInt()
                ausserhausOldValue1 = editTextNumberAusserhausOldValue1.text.toString().toInt()
                ausserhausOldValue2 = editTextNumberAusserhausOldValue2.text.toString().toInt()
                imhausNewValue1 = editTextNumberImhausNewValue1.text.toString().toInt()
                imhausNewValue2 = editTextNumberImhausNewValue2.text.toString().toInt()
                imhausOldValue1 = editTextNumberImhausOldValue1.text.toString().toInt()
                imhausOldValue2 = editTextNumberImhausOldValue2.text.toString().toInt()
                openFile("Select Artikellen.csv file to change", SELECT_SPEEDY_EXPORT_CSV_FILE_REQUEST_CODE)
            } catch (e: NumberFormatException) {
                Toast.makeText(this, e.message, Toast.LENGTH_SHORT).show()
            }
        }
        editTextNumberAusserhausOldValue1.setText(ausserhausOldValue1.toString())
        editTextNumberAusserhausOldValue2.setText(ausserhausOldValue2.toString())
        editTextNumberImhausOldValue1.setText(imhausOldValue1.toString())
        editTextNumberImhausOldValue2.setText(imhausOldValue2.toString())
    }

    private fun openFile(title: String, requestCode: Int) {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = "*/*"
        startActivityForResult(
            Intent.createChooser(intent, title),
            requestCode
        )
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
            SELECT_ELBEPOS_EXPORT_CSV_FILE_REQUEST_CODE -> {
                if (resultCode == RESULT_OK) {
                    if (data?.data != null) {
                        elbeposExportFile = de.elbepos.speedyconverter.FileUtils.getFile(this, data.data)
                        fileTextView.text = elbeposExportFile?.name

                        showLoading()
                        executorService.execute {
                            if (readData()) {
                                writeCategoryCSV()
                                writeItemCSV()
                            }
                            mainThreadHandler.post {
                                hideLoading()
                                Toast.makeText(
                                    this,
                                    "CSV files successfully created under csv folder at internal storage",
                                    Toast.LENGTH_SHORT
                                ).show()

                            }
                        }
                    } else {
                        fileTextView.text = getString(R.string.no_file_selected_title)
                    }
                }
            }
            SELECT_SPEEDY_EXPORT_CSV_FILE_REQUEST_CODE -> {
                if (resultCode == RESULT_OK && data?.data != null) {
                    try {
                        speedyExportFile = de.elbepos.speedyconverter.FileUtils.getFile(this, data.data)
                        fileTextViewSpeedy.text = speedyExportFile?.name ?: getString(R.string.no_file_selected_title)
                        if (speedyExportFile?.exists() == true) {
//                            showLoading()
                            val task = MyAsyncTask(
                                before = { showLoading() },
                                handler = { myTask() },
                                after = {
                                    hideLoading()
                                    Toast.makeText(this, "Successfully converted tax values", Toast.LENGTH_SHORT).show()
                                }
                            )
                            task.execute()

//                            executorService.execute {
//                                val lines = readSpeedyData()
//                                if (lines.isNotEmpty()) {
//                                    writeCSV(ARTIKEL_CSV_FILE_NAME, lines)
//                                    mainThreadHandler.post {
//                                        hideLoading()
//                                        Toast.makeText(this, "Successfully converted tax values", Toast.LENGTH_SHORT).show()
//                                    }
//                                } else {
//                                    mainThreadHandler.post {
//                                        hideLoading()
//                                        Toast.makeText(this, "Error occured", Toast.LENGTH_SHORT).show()
//                                    }
//                                }
//
//                            }
                        }
                    } catch (e: Exception) {
                        Toast.makeText(this, e.message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun myTask() {
        val lines = readSpeedyData()
        writeCSV(ARTIKEL_CSV_FILE_NAME, lines)
    }

    private fun readSpeedyData(): MutableList<Array<String>> {
        try {
            var steuersatzIndex = 0
            var steuersatz2Index = 0
            val lines = mutableListOf<Array<String>>(ARTIKEL_HEADER)
            val reader = FileReader(speedyExportFile).buffered()
            val iterator = reader.lineSequence().iterator()
            while (iterator.hasNext()) {
                val line = iterator.next()
                val tokens = line.split(";").toMutableList()
                if (tokens[0] == "ArtikelID") {
                    steuersatzIndex = tokens.indexOf("Steuersatz")
                    steuersatz2Index = tokens.indexOf("Steuersatz2")
                } else {
                    if (steuersatzIndex != -1) {
                        tokens[steuersatzIndex] = when (tokens[steuersatzIndex]) {
                            imhausOldValue1.toString() -> imhausNewValue1.toString()
                            imhausOldValue2.toString() -> imhausNewValue2.toString()
                            else -> tokens[steuersatzIndex]
                        }
                    }
                    if (steuersatz2Index != -1) {
                        tokens[steuersatz2Index] = when (tokens[steuersatz2Index]) {
                            ausserhausOldValue1.toString() -> ausserhausNewValue1.toString()
                            ausserhausOldValue2.toString() -> ausserhausNewValue2.toString()
                            else -> tokens[steuersatz2Index]
                        }
                    }
                    lines.add(tokens.toTypedArray())
                }
//                if (tokens[ARTIKEL_HEADER.indexOf("ArtikelID")] != "ArtikelID") {
//                    val steuersatzIndex = ARTIKEL_HEADER.indexOf("Steuersatz")
//
//
//                    val steuersatz2Index = ARTIKEL_HEADER.indexOf("Steuersatz2")


//                    val idIndex = ARTIKEL_HEADER.indexOf("ArtikelID")
//                    val numberIndex = ARTIKEL_HEADER.indexOf("Artikelnummer")
//                    val nameIndex = ARTIKEL_HEADER.indexOf("ArtikelTextKurz")
//                    val categoryIdIndex = ARTIKEL_HEADER.indexOf("ArtikelGruppenID")
//                    val sortIndexIndex = ARTIKEL_HEADER.indexOf("SortierIndex")
//                    val salePriceIndex = ARTIKEL_HEADER.indexOf("Verkaufspreis")
//                    val depositPriceIndex = ARTIKEL_HEADER.indexOf("Pfandpreis")
//                    val linedItem = TargetItem(
//                        id = tokens[idIndex],
//                        number = tokens[numberIndex],
//                        name = tokens[nameIndex],
//                        categoryId = tokens[categoryIdIndex],
//                        sortIndex = tokens[sortIndexIndex].toInt(),
//                        salePrice = formatter!!.format(tokens[salePriceIndex].toDouble() ?: 0.00),
//                        vat = newImhausVat,
//                        vat2 = newAusserhausVat,
//                        depositPrice = formatter!!.format(tokens[depositPriceIndex].toDouble())
//                    ).toLine()

//                }
            }
            reader.close()
            return lines
        } catch (e: IOException) {
            Log.e("MainActivity", e.message)
            return mutableListOf<Array<String>>()
        }
    }

//    private fun writeCSV(filename: String, lines: List<Array<String>>): Boolean {
//        var writer: CSVWriter? = null
//        try {
//            val folder =
//                File(Environment.getExternalStorageDirectory().toString() + "/csv")
//            folder.mkdirs()
//            val extStorageDirectory = folder.toString()
//            val file = File(extStorageDirectory, filename)
//            if (file.exists()) {
//                file.createNewFile()
//            }
//            val fileWriter = FileWriter(file, false)
//            writer = CSVWriter(
//                fileWriter,
//                ';',
//                CSVWriter.NO_QUOTE_CHARACTER,
//                CSVWriter.DEFAULT_ESCAPE_CHARACTER,
//                CSVWriter.DEFAULT_LINE_END
//            )
//            writer.writeAll(lines)
//        } catch (e: java.lang.Exception) {
//            hideLoading()
//            Log.e("MainActivity", e.message)
//            return false
//        } finally {
//            writer?.close()
//        }
//        return true
//    }

//    private class ReadFileTask(val context: Context, val file: File) : AsyncTask<Void, Void, Void>() {
//        private var progressDialog: ProgressDialog? = null
//
//        override fun onPreExecute() {
//            super.onPreExecute()
//            showLoading()
//        }
//
//        override fun doInBackground(vararg p0: Void?): Void {
//            val lines = readSpeedyData()
//            writeCSV(ARTIKEL_CSV_FILE_NAME, lines)
//            return Void()
//        }
//
//        private fun showLoading() {
//            progressDialog = ProgressDialog.show(context, "", "Creating import csv files")
//        }
//
//        private fun hideLoading() {
//            progressDialog?.dismiss()
//        }
//
//
//
//    }

    class MyAsyncTask(
        val before: (() -> Unit)? = null,
        val handler: () -> Unit,
        val after: (() -> Unit)? = null,
    ) : AsyncTask<Void, Void, Void>() {
        override fun onPreExecute() {
            super.onPreExecute()
            before?.invoke()
        }
        override fun doInBackground(vararg params: Void?): Void? {
            handler()
            return null
        }

        override fun onPostExecute(result: Void?) {
            super.onPostExecute(result)
            after?.invoke()
        }
    }



    private fun getNumberFormat(): NumberFormat {
        val numberFormat = NumberFormat.getInstance()
        numberFormat.minimumFractionDigits = 2
        numberFormat.maximumFractionDigits = 2
        val currency = Currency.getInstance("EUR")
        numberFormat.currency = currency
        return numberFormat
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

    private fun getCsvFile(filename: String): File {
        val folder =
            File(Environment.getExternalStorageDirectory().toString() + "/csv")
        if (!folder.exists()) {
            folder.mkdirs()
        }
        val extStorageDirectory = folder.toString()
        return File(extStorageDirectory, filename)
    }

    private fun readData(): Boolean {
        try {
//            val reader = resources.openRawResource(R.raw.test).reader()
            if (elbeposExportFile != null) {
                sourceItems.clear()
                val reader = FileReader(elbeposExportFile).buffered()
                val iterator = reader.lineSequence().iterator()
                while(iterator.hasNext()) {
                    val line = iterator.next()
                    // do something with line...
                    val item = lineToSourceItem(line)
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
            val vat = sourceItem.getTaxPercentage()
            TargetItem(
                id = (index).toString(),
                number = sourceItem.gtin,
                name = sourceItem.name.replace("\"", ""),
                categoryId = categoryId,
                sortIndex = index * 10,
                salePrice = formatter!!.format(sourceItem.price_per_unit ?: 0.00),
                vat = vat,
                vat2 = vat,
                depositPrice = formatter!!.format(sourceItem.getDepositPrice())
            )
        }.toMutableList()
        targetItems.removeAt(0)
        return targetItems
    }



    private fun lineToSourceItem(line: String): SourceItem {
        val tokens = line.split(";")
        return SourceItem(
            type = tokens[0],
            price_per_unit = tokens[1].replace("\"", "").toDoubleOrNull(),
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

    private fun lineToTargetItem(line: String): TargetItem {
        val tokens = line.split(";").toMutableList()
        val newVat = when (tokens[13]) {
//            "16" -> inhausVat.toString()
//            "5" -> ausserhausVat.toString()
            else -> "0"
        }
        return TargetItem(
            id = tokens[0],
            number = tokens[2],
            name = tokens[1],
            categoryId = tokens[6],
            sortIndex = tokens[7].toInt(),
            salePrice = "0.00",
            vat = newVat,
            vat2 = newVat,
            depositPrice = "0.00"
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
            else -> "5"
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
    fun getDepositPrice(): Double {
        var price = 0.00
        if (pfand_type == null) return price
        price = when (pfand_type) {
            "Mehrweg-Bierflasche aus Glas" -> 0.08
            "Mehrweg-Bierflasche mit Bügelverschluss" -> 0.15
            "Mehrwegflaschen für Saft oder Softdrinks" -> 0.15
            "Einwegflaschen und -dosen" -> 0.25
            else -> 0.00
        }
        return price
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
    val unitPrice: String = "0.00",
    val salePrice: String,
    val discountId: String = "",
    val vat: String,
    val vat2: String,
    val depositPrice: String = "0.00",
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
        salePrice: String,
        vat: String,
        vat2: String,
        depositPrice: String?
    ): this(
        id = id,
        number = number,
        shortName = name,
        longName = name,
        categoryId = categoryId,
        sortIndex = sortIndex,
        salePrice = salePrice,
        vat = vat,
        vat2 = vat2,
        depositPrice = depositPrice ?: "0.00"
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
            unitPrice,
            salePrice,
            discountId,
            vat,
            vat2,
            depositPrice,
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



//    override fun toString(): String {
//        return arrayOf(
//            id, itemType.toString(), number, shortName, longName, comment,
//            sortIndex.toString(), quantityMeasure, quantityNumberAfterDecimalPoint.toString(),
//            unitPrice.toFormatted(), salePrice.toFormatted(), discountId, vat, vat2, belegReceipt,
//            depositReceipt, status.toString(), bookingBehaviour.toString(), variantText,
//            salePriceChangeable, itemTextChangeable, noteChangeable, rating.toString(),
//            storeLocationId, itemImage, cashOrder
//        ).joinToString(",")
//    }
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