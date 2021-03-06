package de.androidcrypto.nfcnfcataghexdump;

import android.content.Context;
import android.content.Intent;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.TagLostException;
import android.nfc.tech.NfcA;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class MainActivityOrg extends AppCompatActivity implements NfcAdapter.ReaderCallback {

    TextView dumpField, readResult;
    private NfcAdapter mNfcAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        dumpField = findViewById(R.id.tvMainDump1);
        readResult = findViewById(R.id.tvMainReadResult);

        mNfcAdapter = NfcAdapter.getDefaultAdapter(this);
    }

    // This method is run in another thread when a card is discovered
    // !!!! This method cannot cannot direct interact with the UI Thread
    // Use `runOnUiThread` method to change the UI from this method
    @Override
    public void onTagDiscovered(Tag tag) {
        // Read and or write to Tag here to the appropriate Tag Technology type class
        // in this example the card should be an Ndef Technology Type

        System.out.println("NFC tag discovered");

        NfcA nfcA = null;

        try {
            nfcA = NfcA.get(tag);

            if (nfcA != null) {
                runOnUiThread(() -> {
                    Toast.makeText(getApplicationContext(),
                            "NFC tag is Nfca compatible",
                            Toast.LENGTH_SHORT).show();
                });

                // Make a Sound
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ((Vibrator) getSystemService(VIBRATOR_SERVICE)).vibrate(VibrationEffect.createOneShot(150, 10));
                } else {
                    Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                    v.vibrate(200);
                }

                nfcA.connect();

                // check that the tag is a NTAG213/215/216 manufactured by NXP - stop if not
                String ntagVersion = NfcIdentifyNtag.checkNtagType(nfcA, tag.getId());
                if (ntagVersion.equals("0")) {
                    runOnUiThread(() -> {
                        readResult.setText("NFC tag is NOT of type NXP NTAG213/215/216");
                        Toast.makeText(getApplicationContext(),
                                "NFC tag is NOT of type NXP NTAG213/215/216",
                                Toast.LENGTH_SHORT).show();
                    });
                    return;
                }

                int nfcaMaxTranceiveLength = nfcA.getMaxTransceiveLength(); // important for the readFast command
                int ntagPages = NfcIdentifyNtag.getIdentifiedNtagPages();
                int ntagMemoryBytes = NfcIdentifyNtag.getIdentifiedNtagMemoryBytes();
                String tagIdString = getDec(tag.getId());
                String nfcaContent = "raw data of " + NfcIdentifyNtag.getIdentifiedNtagType() + "\n" +
                        "number of pages: " + ntagPages +
                        " total memory: " + ntagMemoryBytes +
                        " bytes\n" +
                        "tag ID: " + bytesToHex(NfcIdentifyNtag.getIdentifiedNtagId()) + "\n" +
                        "tag ID: " + tagIdString + "\n";
                nfcaContent = nfcaContent + "maxTranceiveLength: " + nfcaMaxTranceiveLength + " bytes\n";
                // read the complete memory depending on ntag type
                byte[] headerMemory = new byte[16]; // 4 pages of each 4 bytes, e.g. manufacturer data
                byte[] ntagMemory = new byte[ntagMemoryBytes]; // user memory, 888 byte for a NTAG216
                byte[] footerMemory = new byte[20]; // 5 pages, e.g. dyn. lock bytes, configuration pages, password & pack
                // read the content of the tag in several runs
                byte[] response;

                // first we are reading the header
                headerMemory = getFastTagDataRange(nfcA, 0, 3);
                if (headerMemory == null) {
                    writeToUiAppend(readResult, "ERROR on reading header, aborted");
                }
                String dumpContentHeader = "Header content:\n" + HexDumpOwn.prettyPrint(headerMemory);
                if (footerMemory == null) {
                    writeToUiAppend(readResult, "ERROR on reading header, aborted");
                }
                int footerStart = 4 + ntagPages;
                int footerEnd = 4 + footerStart;
                footerMemory = getFastTagDataRange(nfcA, footerStart, footerEnd);
                String dumpContentFooter = "Footer content:\n" + HexDumpOwn.prettyPrint(footerMemory);


                try {
                    //int nfcaMaxTranceiveLength = nfcA.getMaxTransceiveLength(); // my device: 253 bytes
                    int nfcaMaxTranceive4ByteTrunc = nfcaMaxTranceiveLength / 4; // 63
                    int nfcaMaxTranceive4ByteLength = nfcaMaxTranceive4ByteTrunc * 4; // 252 bytes
                    int nfcaNrOfFullReadings = ntagMemoryBytes / nfcaMaxTranceive4ByteLength; // 888 bytes / 252 bytes = 3 full readings
                    int nfcaTotalFullReadingBytes = nfcaNrOfFullReadings * nfcaMaxTranceive4ByteLength; // 3 * 252 = 756
                    int nfcaMaxTranceiveModuloLength = ntagMemoryBytes - nfcaTotalFullReadingBytes; // 888 bytes - 756 bytes = 132 bytes
                    nfcaContent = nfcaContent + "nfcaMaxTranceive4ByteTrunc: " + nfcaMaxTranceive4ByteTrunc + "\n";
                    nfcaContent = nfcaContent + "nfcaMaxTranceive4ByteLength: " + nfcaMaxTranceive4ByteLength + "\n";
                    nfcaContent = nfcaContent + "nfcaNrOfFullReadings: " + nfcaNrOfFullReadings + "\n";
                    nfcaContent = nfcaContent + "nfcaTotalFullReadingBytes: " + nfcaTotalFullReadingBytes + "\n";
                    nfcaContent = nfcaContent + "nfcaMaxTranceiveModuloLength: " + nfcaMaxTranceiveModuloLength + "\n";

                    for (int i = 0; i < nfcaNrOfFullReadings; i++) {
                        System.out.println("starting round: " + i);
                        byte[] commandF = new byte[]{
                                (byte) 0x3A,  // FAST_READ
                                (byte) ((4 + (nfcaMaxTranceive4ByteTrunc * i)) & 0x0ff), // page 4 is the first user memory page
                                (byte) ((4 + (nfcaMaxTranceive4ByteTrunc * (i + 1)) - 1) & 0x0ff)
                        };
                        //nfcaContent = nfcaContent + "i: " + i + " commandF: " + bytesToHex(commandF) + "\n";
                        response = nfcA.transceive(commandF);
                        if (response == null) {
                            // either communication to the tag was lost or a NACK was received
                            // Log and return
                            nfcaContent = nfcaContent + "ERROR: null response";
                            String finalNfcaText = nfcaContent;
                            runOnUiThread(() -> {
                                readResult.setText(finalNfcaText);
                                System.out.println(finalNfcaText);
                            });
                            return;
                        } else if ((response.length == 1) && ((response[0] & 0x00A) != 0x00A)) {
                            // NACK response according to Digital Protocol/T2TOP
                            // Log and return
                            nfcaContent = nfcaContent + "ERROR: NACK response: " + bytesToHex(response);
                            String finalNfcaText = nfcaContent;
                            runOnUiThread(() -> {
                                readResult.setText(finalNfcaText);
                                System.out.println(finalNfcaText);
                            });
                            return;
                        } else {
                            // success: response contains ACK or actual data
                            // nfcaContent = nfcaContent + "successful reading " + response.length + " bytes\n";
                            System.arraycopy(response, 0, ntagMemory, (nfcaMaxTranceive4ByteLength * i), nfcaMaxTranceive4ByteLength);
                        }
                    } // for

                    // now we read the nfcaMaxTranceiveModuloLength bytes, for a NTAG216 = 132 bytes
                    //nfcaContent = nfcaContent + "starting last round: " + "\n";
                    //System.out.println("starting last round: ");
                    byte[] commandF = new byte[]{
                            (byte) 0x3A,  // FAST_READ
                            (byte) ((4 + (nfcaMaxTranceive4ByteTrunc * nfcaNrOfFullReadings)) & 0x0ff), // page 4 is the first user memory page
                            (byte) ((4 + (nfcaMaxTranceive4ByteTrunc * nfcaNrOfFullReadings) + (nfcaMaxTranceiveModuloLength / 4) & 0x0ff))
                    };
                    //nfcaContent = nfcaContent + "last: " + " commandF: " + bytesToHex(commandF) + "\n";
                    response = nfcA.transceive(commandF);
                    if (response == null) {
                        // either communication to the tag was lost or a NACK was received
                        // Log and return
                        nfcaContent = nfcaContent + "ERROR: null response";
                        String finalNfcaText = nfcaContent;
                        runOnUiThread(() -> {
                            readResult.setText(finalNfcaText);
                            System.out.println(finalNfcaText);
                        });
                        return;
                    } else if ((response.length == 1) && ((response[0] & 0x00A) != 0x00A)) {
                        // NACK response according to Digital Protocol/T2TOP
                        // Log and return
                        nfcaContent = nfcaContent + "ERROR: NACK response: " + bytesToHex(response);
                        String finalNfcaText = nfcaContent;
                        runOnUiThread(() -> {
                            readResult.setText(finalNfcaText);
                            System.out.println(finalNfcaText);
                        });
                        return;
                    } else {
                        // success: response contains ACK or actual data
                        // nfcaContent = nfcaContent + "successful reading " + response.length + " bytes\n";
                        // nfcaContent = nfcaContent + bytesToHex(response) + "\n";
                        // copy the response to the ntagMemory
                        //nfcaContent = nfcaContent + "number of bytes read: : " + response.length + "\n";
                        //nfcaContent = nfcaContent + "response:\n" + bytesToHex(response) + "\n";
                        System.arraycopy(response, 0, ntagMemory, (nfcaMaxTranceive4ByteLength * nfcaNrOfFullReadings), nfcaMaxTranceiveModuloLength);
                    }

                    nfcaContent = nfcaContent + "fast reading complete: " + "\n" + bytesToHex(ntagMemory) + "\n";
                } catch (TagLostException e) {
                    // Log and return
                    nfcaContent = nfcaContent + "ERROR: Tag lost exception";
                    String finalNfcaText = nfcaContent;
                    runOnUiThread(() -> {
                        readResult.setText(finalNfcaText);
                        System.out.println(finalNfcaText);
                    });
                    return;
                } catch (IOException e) {

                    e.printStackTrace();

                }
                String finalNfcaRawText = nfcaContent;
                String dumpContent = dumpContentHeader + "\n\nUser memory content:\n" + HexDumpOwn.prettyPrint(ntagMemory);
                dumpContent = dumpContent + "\n\nUser memory content:\n" + dumpContentFooter;
                String finalNfcaText = "parsed content:\n" + new String(ntagMemory, StandardCharsets.US_ASCII);
                String finalDumpContent = dumpContent;
                runOnUiThread(() -> {
                    dumpField.setText(finalDumpContent);
                    readResult.setText(finalNfcaRawText);
                    //nfcContentParsed.setText(finalNfcaText);
                    System.out.println(finalNfcaRawText);
                });
            } else {
                runOnUiThread(() -> {
                    Toast.makeText(getApplicationContext(),
                            "NFC tag is NOT Nfca compatible",
                            Toast.LENGTH_SHORT).show();
                });
            }
        } catch (IOException e) {
            //Trying to catch any ioexception that may be thrown
            e.printStackTrace();
        } catch (Exception e) {
            //Trying to catch any exception that may be thrown
            e.printStackTrace();

        } finally {
            try {
                nfcA.close();
            } catch (IOException e) {
            }
        }
    }



    public static String bytesToHex(byte[] bytes) {
        StringBuffer result = new StringBuffer();
        for (byte b : bytes) result.append(Integer.toString((b & 0xff) + 0x100, 16).substring(1));
        return result.toString();
    }

    private String getDec(byte[] bytes) {
        long result = 0;
        long factor = 1;
        for (int i = 0; i < bytes.length; ++i) {
            long value = bytes[i] & 0xffl;
            result += value * factor;
            factor *= 256l;
        }
        return result + "";
    }

    private void writeToUiAppend(TextView textView, String message) {
        runOnUiThread(() -> {
            String newString = message + "\n" + textView.getText().toString();
            textView.setText(newString);
        });
    }

    private void writeToUiToast(String message) {
        runOnUiThread(() -> {
            Toast.makeText(getApplicationContext(),
                    message,
                    Toast.LENGTH_SHORT).show();
        });
    }

    private boolean getTagData(NfcA nfcA, int page, EditText pageData1, EditText pageData2, EditText pageData3, EditText pageData4, EditText resultText) {
        boolean result;
        byte[] response;
        byte[] command = new byte[]{
                (byte) 0x30,  // READ
                (byte) (page & 0x0ff), // page 0
        };
        try {
            response = nfcA.transceive(command); // response should be 16 bytes = 4 pages
            if (response == null) {
                // either communication to the tag was lost or a NACK was received
                runOnUiThread(() -> {
                    resultText.setText("ERROR: null response");
                    pageData1.setText("no data");
                    if (pageData2 != null) {
                        pageData2.setText("no data");
                    }
                    if (pageData3 != null) {
                        pageData3.setText("no data");
                    }
                    if (pageData4 != null) {
                        pageData4.setText("no data");
                    }
                });
                return false;
            } else if ((response.length == 1) && ((response[0] & 0x00A) != 0x00A)) {
                // NACK response according to Digital Protocol/T2TOP
                // Log and return
                runOnUiThread(() -> {
                    resultText.setText("ERROR: NACK response: " + Utils.bytesToHex(response));
                    pageData1.setText("response: " + Utils.bytesToHex(response));
                    if (pageData2 != null) {
                        pageData2.setText("response: " + Utils.bytesToHex(response));
                    }
                    if (pageData3 != null) {
                        pageData3.setText("response: " + Utils.bytesToHex(response));
                    }
                    if (pageData4 != null) {
                        pageData4.setText("response: " + Utils.bytesToHex(response));
                    }
                });
                return false;
            } else {
                // success: response contains ACK or actual data
                runOnUiThread(() -> {
                    resultText.setText("SUCCESS: response: " + Utils.bytesToHex(response));
                    // split the response
                    byte[] res1 = new byte[4];
                    byte[] res2 = new byte[4];
                    byte[] res3 = new byte[4];
                    byte[] res4 = new byte[4];
                    System.arraycopy(response, 0, res1, 0, 4);
                    System.arraycopy(response, 4, res2, 0, 4);
                    System.arraycopy(response, 8, res3, 0, 4);
                    System.arraycopy(response, 12, res4, 0, 4);
                    pageData1.setText(Utils.bytesToHex(res1));
                    System.out.println("page " + page + ": " + Utils.bytesToHex(res1));
                    if (pageData2 != null) {
                        pageData2.setText(Utils.bytesToHex(res2));
                        System.out.println("page " + (page + 1) + ": " + Utils.bytesToHex(res2));
                    }
                    if (pageData3 != null) {
                        pageData3.setText(Utils.bytesToHex(res3));
                        System.out.println("page " + (page + 2) + ": " + Utils.bytesToHex(res3));
                    }
                    if (pageData4 != null) {
                        pageData4.setText(Utils.bytesToHex(res4));
                        System.out.println("page " + (page + 3) + ": " + Utils.bytesToHex(res4));
                    }
                });
                System.out.println("page " + page + ": " + Utils.bytesToHex(response));
                result = true;
            }
        } catch (TagLostException e) {
            // Log and return
            runOnUiThread(() -> {
                readResult.setText("ERROR: Tag lost exception");
            });
            return false;
        } catch (IOException e) {
            runOnUiThread(() -> {
                resultText.setText("IOException: " + e.toString());
            });
            e.printStackTrace();
            return false;
        }
        return result;
    }

    private byte[] getFastTagDataRange(NfcA nfcA, int fromPage, int toPage) {
        byte[] response;
        byte[] command = new byte[]{
                (byte) 0x3A,  // FAST_READ
                (byte) (fromPage & 0x0ff),
                (byte) (toPage & 0x0ff),
        };
        try {
            response = nfcA.transceive(command); // response should be 16 bytes = 4 pages
            if (response == null) {
                // either communication to the tag was lost or a NACK was received
                writeToUiAppend(readResult, "ERROR on reading page");
                return null;
            } else if ((response.length == 1) && ((response[0] & 0x00A) != 0x00A)) {
                // NACK response according to Digital Protocol/T2TOP
                writeToUiAppend(readResult, "ERROR NACK received");
                // Log and return
                return null;
            } else {
                // success: response contains ACK or actual data
            }
        } catch (TagLostException e) {
            // Log and return
            writeToUiAppend(readResult, "ERROR Tag lost exception");
            return null;
        } catch (IOException e) {
            writeToUiAppend(readResult, "ERROR IOException: " + e);
            e.printStackTrace();
            return null;
        }
        return response;
    }

    private void showWirelessSettings() {
        Toast.makeText(this, "You need to enable NFC", Toast.LENGTH_SHORT).show();
        Intent intent = new Intent(Settings.ACTION_WIRELESS_SETTINGS);
        startActivity(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (mNfcAdapter != null) {

            if (!mNfcAdapter.isEnabled())
                showWirelessSettings();

            Bundle options = new Bundle();
            // Work around for some broken Nfc firmware implementations that poll the card too fast
            options.putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250);

            // Enable ReaderMode for all types of card and disable platform sounds
            // the option NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK is NOT set
            // to get the data of the tag afer reading
            mNfcAdapter.enableReaderMode(this,
                    this,
                    NfcAdapter.FLAG_READER_NFC_A |
                            NfcAdapter.FLAG_READER_NFC_B |
                            NfcAdapter.FLAG_READER_NFC_F |
                            NfcAdapter.FLAG_READER_NFC_V |
                            NfcAdapter.FLAG_READER_NFC_BARCODE |
                            NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
                    options);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mNfcAdapter != null)
            mNfcAdapter.disableReaderMode(this);
    }
}