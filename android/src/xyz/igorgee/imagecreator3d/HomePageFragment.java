package xyz.igorgee.imagecreator3d;

import android.app.Activity;
import android.app.Fragment;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.support.design.widget.Snackbar;
import android.support.v7.widget.GridLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.soundcloud.android.crop.Crop;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;

import butterknife.Bind;
import butterknife.ButterKnife;
import butterknife.OnClick;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;
import xyz.igorgee.Api.ServerInterface;
import xyz.igorgee.floatingactionbutton.FloatingActionsMenu;
import xyz.igorgee.shapejs.ShapeJS;
import xyz.igorgee.utilities.ImageHelper;
import xyz.igorgee.utilities.JavaUtilities;

import static xyz.igorgee.utilities.UIUtilities.makeAlertDialog;
import static xyz.igorgee.utilities.UIUtilities.makeSnackbar;

public class HomePageFragment extends Fragment {

    private final static int TAKE_PICTURE = 7428873;
    private final static String MODELS_DIRECTORY_NAME = "models";
    public static final String MODEL_NAMES = "ModelNames";

    public static SharedPreferences sharedPreferences;

    public static File filesDirectory;
    public static File modelsDirectory;

    @Bind(R.id.empty_home_page_text) TextView textView;
    @Bind(R.id.image_options_fam) FloatingActionsMenu fam;
    @Bind(R.id.list) RecyclerView list;

    ArrayList<Model> models;
    CustomAdapter adapter;
    RecyclerView.LayoutManager linearLayoutManager;

    public static final String BASE_URL = "http://52.90.86.247/";
    private static Retrofit retrofit = new Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build();
    public static final ServerInterface apiService = retrofit.create(ServerInterface.class);

    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view =  inflater.inflate(R.layout.fragment_homepage, container, false);
        ButterKnife.bind(this, view);

        setHasOptionsMenu(true);

        filesDirectory = Environment.getExternalStorageDirectory();
        modelsDirectory = new File(filesDirectory, MODELS_DIRECTORY_NAME);
        modelsDirectory.mkdirs();

        list.setHasFixedSize(true);
        linearLayoutManager = new GridLayoutManager(getActivity(), 1);
        list.setLayoutManager(linearLayoutManager);

        models = new ArrayList<>();
        adapter = CustomAdapter.getInstance(getActivity(), models);
        list.setAdapter(adapter);

        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                if (dy > 0) {
                    fam.hide();
                } else {
                    fam.show();
                }
            }
        });

        sharedPreferences = getActivity().getSharedPreferences
                (HomePageFragment.MODEL_NAMES, Context.MODE_PRIVATE);

        return view;
    }

    public void refresh() {
        adapter.updateList(models);
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        checkExistingFiles();
    }

    private void checkExistingFiles() {
        if (modelsDirectory.listFiles() != null) {
            for (final File directory : modelsDirectory.listFiles()) {
                for (final File file : directory.listFiles()) {
                    if (file.getName().endsWith(".stl")) {
                        String fileName = file.getName().substring(0, file.getName().length() - 4);
                        models.add(new Model(fileName, directory));
                        textView.setVisibility(View.GONE);
                    }
                }
                Collections.reverse(models);
                refresh();
            }
        }
    }

    @OnClick(R.id.gallery_fab)
    public void selectImage(View view) {
        Crop.pickImage(getActivity(), this);
        fam.collapse();
    }

    @OnClick(R.id.camera_fab)
    public void takePicture(View view) {
        Intent cameraIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        if (cameraIntent.resolveActivity(getActivity().getPackageManager()) != null) {
            startActivityForResult(cameraIntent, TAKE_PICTURE);
        } else {
            makeSnackbar(view, "Sorry you need to have a camera app.");
        }
        fam.collapse();
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (resultCode == Activity.RESULT_OK && data != null) {
            if (requestCode == Crop.REQUEST_PICK || requestCode == TAKE_PICTURE) {
                Uri pickedImage = data.getData();
                File source = new File(getRealPathFromURI(pickedImage));


                Log.d("FILEPATHS", source.exists() + " " + source.getAbsolutePath());
                Log.d("FILEPATHS", pickedImage.getPath());
                Log.d("FILEPATHS", String.valueOf(pickedImage));

                if (source.exists()) {
                    Uri destination = Uri.fromFile(new File(getActivity().getCacheDir(), source.getName()));
                    Crop.of(pickedImage, destination).asSquare().start(getActivity(), this);
                } else {
                    makeAlertDialog(getActivity(), "File doesn't exist", "Your gallery application may have a saved file of an image that doesn't exist on your local storage.");
                }
            } else if (requestCode == Crop.REQUEST_CROP) {
                Uri croppedImage = Crop.getOutput(data);
                File imagePath = new File(croppedImage.getPath());
                new GenerateObject(imagePath, getActivity()).execute();
                textView.setVisibility(View.GONE);
            }
        }
    }

    private String getRealPathFromURI(Uri contentURI) {
        String result;
        Cursor cursor = getActivity().getContentResolver().query(contentURI, null, null, null, null);
        if (cursor == null) { // Source is Dropbox or other similar local file path
            result = contentURI.getPath();
        } else {
            cursor.moveToFirst();
            int idx = cursor.getColumnIndex(MediaStore.Images.ImageColumns.DATA);
            result = cursor.getString(idx);
            cursor.close();
        }
        return result;
    }

    private class GenerateObject extends AsyncTask<Void, Void, Void> {

        File file;
        String filename;
        File modelDirectory;
        Bitmap bitmap;
        Context context;
        ShapeJS shapeJS = new ShapeJS();
        boolean error = false;

        GenerateObject(File file, Context context) {
            this.file = file;
            if (file.getName().contains("."))
                filename = file.getName().substring(0, file.getName().indexOf('.'));
            else
                filename = file.getName();
            this.context = context;
            Log.d("FILENAMEDATE", filename);
            String modelDirectoryName = (filename + new Date().toString()).replace(" ", "");
            for (Character c : JavaUtilities.ILLEGAL_CHARACTERS)
                modelDirectoryName = modelDirectoryName.replace(c.toString(), "");
            modelDirectory = new File(modelsDirectory, modelDirectoryName);
            modelDirectory.mkdirs();
            Log.d("FILELOCATION", modelDirectory.getAbsolutePath());
            Log.d("FILENAMEDATE", modelDirectory.getName());
            bitmap = ImageHelper.decodeSampledBitmapFromResource(file.getAbsoluteFile());
        }

        @Override
        protected Void doInBackground(Void... params) {
            InputStream inputStream = null;
            FileOutputStream outputStream = null;
            FileOutputStream bitmapOutputStream = null;
            File zipFile = new File(modelDirectory, filename + ".zip");

            try {
                makeSnackbar(textView, "Uploaded!\nGenerating model. Please wait.", Snackbar.LENGTH_LONG);
                inputStream = shapeJS.uploadImage(file);
                outputStream = new FileOutputStream(zipFile);

                int b;
                while ((b = inputStream.read()) != -1) {
                    outputStream.write(b);
                }

                JavaUtilities.unzip(zipFile, modelDirectory);

                for (File file : modelDirectory.listFiles()) {
                    if (file.getName().endsWith(".jpg")) {
                        file.renameTo(new File(modelDirectory, "preview.jpg"));
                    } else {
                        file.renameTo(new File(modelDirectory,
                                filename + file.getName().substring(file.getName().indexOf('.'))));
                    }
                }

                bitmapOutputStream = new FileOutputStream(
                        new File(modelDirectory, filename + ".jpg"));
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, bitmapOutputStream);

            } catch (IOException e) {
                error = true;
                e.printStackTrace();
            } finally {
                try {
                    if (!zipFile.delete())
                        Log.e("GENERATEOBJECT", "Zip file wasn't deleted");
                    if (inputStream != null)
                        inputStream.close();
                    if (outputStream != null)
                        outputStream.close();
                    if (bitmapOutputStream != null)
                        bitmapOutputStream.close();
                } catch (IOException e ) {
                    e.printStackTrace();
                }
            }

            return null;
        }

        @Override
        protected void onPostExecute(Void aVoid) {
            super.onPostExecute(aVoid);

            if (error) {
                makeAlertDialog(context, "Error", "Sorry, something went wrong. Try again in a few minutes.");
            } else {
                final Model model = new Model(filename, modelDirectory);
                models.add(0, model);
                list.smoothScrollToPosition(0);
                refresh();
            }
        }
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.homepage_toolbar, menu);
        super.onCreateOptionsMenu(menu, inflater);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        switch (id) {
            case R.id.cart:
                String urlString="https://shapeways.com/shops/carbonizr";
                Intent intent=new Intent(Intent.ACTION_VIEW, Uri.parse(urlString));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                intent.setPackage("com.android.chrome");
                try {
                    this.startActivity(intent);
                } catch (ActivityNotFoundException ex) {
                    // Chrome browser presumably not installed so allow user to choose instead
                    intent.setPackage(null);
                    this.startActivity(intent);
                }
                break;
            case R.id.faq:
                getFragmentManager()
                        .beginTransaction()
                        .replace(R.id.fragmentPlaceholder, new FAQFragment(), "FAQ")
                        .addToBackStack(null)
                        .commit();
                MainActivity.drawerToggle.setDrawerIndicatorEnabled(false);
        }

        return super.onOptionsItemSelected(item);
    }
}
