#include"hdf5.h"
#include <stdlib.h>
#include <string.h>
#include <fstream>
#include <iostream>
#include <ctime>
#include <iomanip>
#include <chrono>
#include <functional>
#include <thread>
#include <string>
#include <random>
#include <stdio.h>
#include<vector>
#include <sys/time.h>

int main(int argc, char *argv[]) {
  printf("Write HDF5 file\n");
  hid_t file, dataset, datatype, dataspace;
  hsize_t dimsf[2];
  herr_t status;
  int NX = 5;
  int NY = 6;
  int data[NX][NY];
  int i, j;
  for (i = 0; i < NX; i++) {
    for (j = 0; j < NY; j++) {
      data[i][j] = i + j;
    }
  }
  //Create HDF5 file
  file = H5Fcreate("MyHDF5.h5", H5F_ACC_TRUNC, H5P_DEFAULT, H5P_DEFAULT);
  dimsf[0] = NX;
  dimsf[1] = NY;
  dataspace = H5Screate_simple(2, dimsf, NULL);
  datatype = H5Tcopy(H5T_NATIVE_INT);
  dataset = H5Dcreate(file, "IntArray", datatype, dataspace, H5P_DEFAULT, H5P_DEFAULT, H5P_DEFAULT);
  status = H5Dwrite(dataset, H5T_NATIVE_INT, H5S_ALL, H5S_ALL, H5P_DEFAULT, data);
  dataset = H5Dcreate(file, "IntArray2", datatype, dataspace, H5P_DEFAULT, H5P_DEFAULT, H5P_DEFAULT);
  status = H5Dwrite(dataset, H5T_NATIVE_INT, H5S_ALL, H5S_ALL, H5P_DEFAULT, data);
  //Set attribute
  int temperature = 41;
  hid_t aid = H5Screate(H5S_SCALAR);
  hid_t attr = H5Acreate(dataset, "Temperature", H5T_NATIVE_INT, aid, H5P_DEFAULT, H5P_DEFAULT);
  status = H5Awrite(attr, H5T_NATIVE_INT, &temperature);
  //Close
  H5Sclose(dataspace);
  H5Sclose(aid);
  H5Tclose(datatype);
  H5Dclose(dataset);
  H5Fclose(file);
  H5Aclose(attr);
  printf("Write HDF5 file succeed\n");
  return 0;
}
