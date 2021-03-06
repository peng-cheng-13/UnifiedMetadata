#include "hdf5.h"
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
#include "mpi.h"
#include "Alluxio.h"
#include "Util.h"
#include "JNIHelper.h"
using namespace tdms;

#define MAX_NAME 1024
//#define H5FILE_NAME    "h5file/MyFile.h5" /* get a better example file... */

void do_dtype(hid_t);
void do_dset(hid_t);
void do_link(hid_t, char *);
void scan_group(hid_t);
void do_attr(hid_t);
void do_attr(hid_t aid, char*, char*);
void scan_attrs(hid_t);
void scan_attrs(char *, hid_t);
void do_plist(hid_t);

jTDMSFileSystem client;
std::string tdmsPath = "/H5test";
std::string ufsPath = "/BIGDATA/nsccgz_pcheng_1/benchmarks/UnifiedMetadata/ExtractMetadata";

int main(int argc, char *argv[]) {

    MPI_Init(&argc, &argv);
    int size;
    int rank;
    MPI_Comm_size(MPI_COMM_WORLD, &size);
    MPI_Comm_rank(MPI_COMM_WORLD, &rank);
    if (rank == 0)
      printf("Init MPI with %d threads\n",size);

    //Init TDMS env
    TDMSClientContext acc;
    TDMSFileSystem stackFS(acc);
    client = &stackFS;
    TDMSCreateFileOptions* options = TDMSCreateFileOptions::getCreateFileOptions();
    //Read path list
    std::vector<std::string> buf;
    std::string s;
    std::ifstream f("path.log");
    while(f >> s) {
      //std::cout << "Read from file: " << s << std::endl;
      buf.push_back(s);
    }
    if (rank == 0)
      printf("Num of string is %d\n", buf.size());

    hid_t    file;
    hid_t    grp;

    herr_t   status;
 
    /*
     **  Example: open a file, open the root, scan the whole file.
     **/
    std::string filepath;
    for(int i = rank; i < buf.size(); i += size) {
      const char *tmpfile = buf[i].data();
      printf("Rank %d processing Path : %s\n", rank, tmpfile);
      file = H5Fopen(tmpfile, H5F_ACC_RDWR, H5P_DEFAULT);
      grp = H5Gopen(file, "/", H5P_DEFAULT);
      scan_group(grp);
      printf("Size of ufs path is %d\n", ufsPath.length());
      if (buf[i].find(ufsPath) < buf[i].length()) {
        filepath = buf[i].replace(0, ufsPath.length(), tdmsPath);
      } else {
        filepath = buf[i];
      }
      jFileOutStream fileOutStream = client->createFile((char*) filepath.data(), options);
      fileOutStream->close();
      client->setDatasetInfo((char*) filepath.data());
      std::cout << "Path of file is " << filepath << std::endl;
      status = H5Fclose(file);
    }
  
    MPI_Finalize();
    return 0;
}

/*
 ** Process a group and all it's members
 **
 **   This can be used as a model to implement different actions and
 **   searches.
 **/

void scan_group(hid_t gid) {
	int i;
	ssize_t len;
	hsize_t nobj;
	herr_t err;
	int otype;
	hid_t grpid, tid, dsid;
	char group_name[MAX_NAME];
	char memb_name[MAX_NAME];

        /*
         ** Information about the group:
         **  Name and attributes
         **
         **  Other info., not shown here: number of links, object id
         **/
	len = H5Iget_name(gid, group_name, MAX_NAME);

	printf("Group Name: %s\n",group_name);

	/*
         **  process the attributes of the group, if any.
         **/
        scan_attrs(gid);

        /*
         **  Get all the members of the groups, one at a time.
         **/
	err = H5Gget_num_objs(gid, &nobj);
        //#pragma omp parallel for
	for (i = 0; i < nobj; i++) {
                /*
                 **  For each object in the group, get the name and
                 **   what type of object it is.
                 **/
		printf("Member: %d; ",i);fflush(stdout);
		len = H5Gget_objname_by_idx(gid, (hsize_t)i, memb_name, (size_t)MAX_NAME);
		printf("Name: %s; ",memb_name);fflush(stdout);
		otype =  H5Gget_objtype_by_idx(gid, (size_t)i );
                /*
                 ** process each object according to its type
                 **/
		switch(otype) {
			case H5G_LINK:
				printf("ObjectType: SYM_LINK:\n");
				do_link(gid,memb_name);
				break;
			case H5G_GROUP:
				printf("ObjectType: GROUP:\n");
				grpid = H5Gopen(gid, memb_name, H5P_DEFAULT);
				scan_group(grpid);
				H5Gclose(grpid);
				break;
			case H5G_DATASET:
				printf("ObjectType: DATASET:\n");
				dsid = H5Dopen(gid,memb_name, H5P_DEFAULT);
				do_dset(dsid);
				H5Dclose(dsid);
				break;
			case H5G_TYPE:
				printf("ObjectType: DATA TYPE:\n");
				tid = H5Topen(gid,memb_name, H5P_DEFAULT);
				do_dtype(tid);
				H5Tclose(tid);
				break;
			default:
				printf("ObjectType: unknown?\n");
				break;
			}

		}
}

/* 
 **  Retrieve information about a dataset.
 **
 **  Many other possible actions.
 **
 **  This example does not read the data of the dataset.
 **/
void do_dset(hid_t did) {
	hid_t tid;
	hid_t pid;
	hid_t sid;
	hsize_t size;
	char ds_name[MAX_NAME];

        /*
         ** Information about the group:
         **  Name and attributes
         **
         **  Other info., not shown here: number of links, object id
         **/
	H5Iget_name(did, ds_name, MAX_NAME  );
	printf("Dataset Name : ");
	puts(ds_name);
	printf("\n");
 
	/*    
         ** Get dataset information: dataspace, data type 
         **/
	sid = H5Dget_space(did); /* the dimensions of the dataset (not shown) */
	tid = H5Dget_type(did);
	printf(" DATA TYPE:\n");
	do_dtype(tid);

        /*
         **  process the attributes of the dataset, if any.
         **/
        printf(" DataSet attribute info:\n");
        //scan_attrs(did);
        scan_attrs(ds_name, did);
        printf("\n");
	/*
         ** Retrieve and analyse the dataset properties
         **/
	pid = H5Dget_create_plist(did); /* get creation property list */
	do_plist(pid);
	size = H5Dget_storage_size(did);
	printf("Total space currently written in file: %d\n",(int)size);
        printf("\n");
        printf("\n");

	/*
         ** The datatype and dataspace can be used to read all or
         ** part of the data.  (Not shown in this example.)
         **/

	  /* ... read data with H5Dread, write with H5Dwrite, etc. */

	H5Pclose(pid);
	H5Tclose(tid);
	H5Sclose(sid);
}

/*
 **  Analyze a data type description
 **/
void do_dtype(hid_t tid) {

	H5T_class_t t_class;
	t_class = H5Tget_class(tid);
	if(t_class < 0){ 
		puts(" Invalid datatype.\n");
	} else {
		/* 
                 ** Each class has specific properties that can be 
                 ** retrieved, e.g., size, byte order, exponent, etc. 
                 **/
		if(t_class == H5T_INTEGER) {
		      puts(" Datatype is 'H5T_INTEGER'.\n");
			/* display size, signed, endianess, etc. */
		} else if(t_class == H5T_FLOAT) {
		      puts(" Datatype is 'H5T_FLOAT'.\n");
			/* display size, endianess, exponennt, etc. */
		} else if(t_class == H5T_STRING) {
		      puts(" Datatype is 'H5T_STRING'.\n");
			/* display size, padding, termination, etc. */
		} else if(t_class == H5T_BITFIELD) {
		      puts(" Datatype is 'H5T_BITFIELD'.\n");
			/* display size, label, etc. */
		} else if(t_class == H5T_OPAQUE) {
		      puts(" Datatype is 'H5T_OPAQUE'.\n");
			/* display size, etc. */
		} else if(t_class == H5T_COMPOUND) {
		      puts(" Datatype is 'H5T_COMPOUND'.\n");
			/* recursively display each member: field name, type  */
		} else if(t_class == H5T_ARRAY) {
		      puts(" Datatype is 'H5T_COMPOUND'.\n");
			/* display  dimensions, base type  */
		} else if(t_class == H5T_ENUM) {
		      puts(" Datatype is 'H5T_ENUM'.\n");
			/* display elements: name, value   */
		} else  {
		      puts(" Datatype is 'Other'.\n");
		      /* and so on ... */
		}
	}
}


/*
 **  Analyze a symbolic link
 **  
 ** The main thing you can do with a link is find out
 ** what it points to.
 **/
void  do_link(hid_t gid, char *name) {
	herr_t status;
	char target[MAX_NAME];

	status = H5Gget_linkval(gid, name, MAX_NAME, target  ) ;
	printf("Symlink: %s points to: %s\n", name, target);
}


/*
 **  Run through all the attributes of a dataset or group. 
 **  This is similar to iterating through a group.
 **/

void scan_attrs(char* datasetName, hid_t oid) {
        int na;
        hid_t aid;
        int i;
        na = H5Aget_num_attrs(oid);
        char* key[na];
        char* value[na];
        //key[1] = "aaa";
        //printf("key is %s", key[1]);
        //char key[128];
        //char value[128];
        for (i = 0; i < na; i++) {
                key[i] = (char *)malloc(128 * sizeof(char));
                value[i] = (char *)malloc(128 * sizeof(char));
                aid =   H5Aopen_idx(oid, (unsigned int)i );
                do_attr(aid, key[i], value[i]);
                //do_attr(aid, &key[0], &value[0]);
                //do_attr(aid);
                printf("Attribute key is %s, value is %s\n", key[i], value[i]);
                H5Aclose(aid);
        }
        //client->addDatasetInfo(datasetName,  key, value, na);
}

void scan_attrs(hid_t oid) {
	int na;
	hid_t aid;
	int i;	
	na = H5Aget_num_attrs(oid);
	for (i = 0; i < na; i++) {
		aid =	H5Aopen_idx(oid, (unsigned int)i );
		do_attr(aid);
		H5Aclose(aid);
        }
}

/*
 * Process one attribute.  
 * This is similar to the information about a dataset.
 */
void do_attr(hid_t aid, char* key, char* value) {
        ssize_t len;
        hid_t atype;
        hid_t aspace;
        char buf[MAX_NAME]; 
        int   rank;
        herr_t ret;

        static std::string attributeV;
        char tmpvalue[8];

        //len = H5Aget_name(aid, MAX_NAME, buf );
        //printf("  Attribute Name : %s\n",buf);
        len = H5Aget_name(aid, MAX_NAME, key);

        aspace = H5Aget_space(aid); /* the dimensions of the attribute data */
        rank = H5Sget_simple_extent_ndims(aspace); /*Determines the dimensionality of a dataspace*/
        hsize_t sdim[64];
        ret = H5Sget_simple_extent_dims(aspace, sdim, NULL); /*Retrieves dataspace dimension size and maximaximum size*/



        atype  = H5Aget_type(aid);
        size_t npoints = H5Sget_simple_extent_npoints(aspace);

	switch(H5Tget_class(atype)) { /*Class type: https://support.hdfgroup.org/HDF5/doc/RM/RM_H5T.html#Datatype-GetClass*/
          case H5T_INTEGER :
          {
            //printf("  Attribute Type : H5T_INTEGER \n");
            int* int_array = (int *)malloc(sizeof(int)*(int)npoints);
            ret = H5Aread(aid, atype, int_array);
            for(int i = 0; i < (int)npoints; i++) {
              sprintf(tmpvalue, "%d ", int_array[i]);
              attributeV.append(tmpvalue);
            }
            sprintf(value, "%s", (char*)attributeV.data());
            printf("%s\n",value);
            attributeV = "";
            free(int_array);
            break;
          }
          case H5T_FLOAT :
          {
            //printf("  Attribute Type : H5T_FLOAT \n");
            float* float_array = (float *)malloc(sizeof(float)*(int)npoints);
            ret = H5Aread(aid, atype, float_array);
            for(int i = 0; i < (int)npoints; i++)
               sprintf(value, "%f ", float_array[i]);
            //printf("\n");
            free(float_array);
            break;
          }
          case H5T_STRING :
          {

            hsize_t sz = H5Aget_storage_size(aid);

            char* char_array = new char[sz+1];
            ret = H5Aread(aid, atype, (void *)char_array);
            //ret = H5Aread(aid, atype, (void *)value);
            sprintf(value, "%s", char_array);
            free(char_array);
            break;
          }
          default:
          {
            printf("UNKNOWN TYPE\n");
          }
        }         
        H5Tclose(atype);
        H5Sclose(aspace);
}


/*
 **  Process one attribute.  
 **  This is similar to the information about a dataset.
 **/
void do_attr(hid_t aid) {
	ssize_t len;
	hid_t atype;
	hid_t aspace;
	char buf[MAX_NAME]; 
        int   rank;
        herr_t ret;
	/* 
         ** Get the name of the attribute.
         **/
	len = H5Aget_name(aid, MAX_NAME, buf );
	printf("  Attribute Name : %s\n",buf);

	/*    
         ** Get attribute information: dataspace, data type 
         **/
	aspace = H5Aget_space(aid); /* the dimensions of the attribute data */
        rank = H5Sget_simple_extent_ndims(aspace); /*Determines the dimensionality of a dataspace*/
        hsize_t sdim[64];
        ret = H5Sget_simple_extent_dims(aspace, sdim, NULL); /*Retrieves dataspace dimension size and maximaximum size*/

        /*Display rank and dimension sizes for the array attribute*/
        if(rank > 0) {
          printf("  Rank : %d \n", rank);
          printf("  Dimension sizes : ");
          for (int i=0; i< rank; i++)
            printf("%d ", (int)sdim[i]);
          printf("\n");
        }

	atype  = H5Aget_type(aid);
        size_t npoints = H5Sget_simple_extent_npoints(aspace);
        printf("  Num of elements in the attribute is %d\n", (int) npoints);
        /*List the value of the attribute*/
        switch(H5Tget_class(atype)) { /*Class type: https://support.hdfgroup.org/HDF5/doc/RM/RM_H5T.html#Datatype-GetClass*/
          case H5T_INTEGER :
          {
            printf("  Attribute Type : H5T_INTEGER \n");
            int* int_array = (int *)malloc(sizeof(int)*(int)npoints);
            ret = H5Aread(aid, atype, int_array);
            printf("  Attribute Values : ");
            for(int i = 0; i < (int)npoints; i++)
              printf("%d ", int_array[i]);
            printf("\n");
            free(int_array);
            break;
          }
          case H5T_FLOAT :
          {
            printf("  Attribute Type : H5T_FLOAT \n");
            float* float_array = (float *)malloc(sizeof(float)*(int)npoints);
            ret = H5Aread(aid, atype, float_array);
            printf("  Attribute Values : ");
            for(int i = 0; i < (int)npoints; i++)
               printf("%f ", float_array[i]);
            printf("\n");
            free(float_array);
            break;
          }
          case H5T_STRING :
          {
            printf("  Attribute Type : H5T_STRING");
            hsize_t sz = H5Aget_storage_size(aid);
            printf(", String size is %d\n",sz);
            char* char_array = new char[sz+1];
            ret = H5Aread(aid, atype, (void *)char_array);
            printf("  Attribute Values : ");
            printf("%s\n",char_array);
            free(char_array);
            break;
          }
          default: 
          {
            printf("UNKNOWN TYPE\n");
          }
        }

	/*
         ** The datatype and dataspace can be used to read all or
         ** part of the data.  (Not shown in this example.)
         **/

	/* ... read data with H5Aread, write with H5Awrite, etc. */

	H5Tclose(atype);
	H5Sclose(aspace);
}

/*
 **   Example of information that can be read from a Dataset Creation 
 **   Property List.
 **
 **   There are many other possibilities, and there are other property
 **   lists.
 **/
void do_plist(hid_t pid) {
	hsize_t chunk_dims_out[2];
	int  rank_chunk;
	int nfilters;
	H5Z_filter_t  filtn;
	int i;
	unsigned int   filt_flags; 
	size_t cd_nelmts;
	unsigned int cd_values[32] ;
	char f_name[MAX_NAME];
	H5D_fill_time_t ft;
	H5D_alloc_time_t at;
	H5D_fill_value_t fvstatus; 
	unsigned int szip_options_mask;
	unsigned int szip_pixels_per_block;

	/* zillions of things might be on the plist */
        /*  here are a few... */

	/*
         ** get chunking information: rank and dimensions.
         **
         **  For other layouts, would get the relevant information.
         **/
	if(H5D_CHUNKED == H5Pget_layout(pid)){
		rank_chunk = H5Pget_chunk(pid, 2, chunk_dims_out);
		printf("chunk rank %d, dimensions %lu x %lu\n", rank_chunk, 
		   (unsigned long)(chunk_dims_out[0]),
		   (unsigned long)(chunk_dims_out[1]));
	} /* else if contiguous, etc. */

	/*
         **  Get optional filters, if any.
         **  This include optional checksum and compression methods.
         **/

	nfilters = H5Pget_nfilters(pid);
	for (i = 0; i < nfilters; i++) 
	{
		/* For each filter, get 
                 **   filter ID filter specific parameters 
                 */
		cd_nelmts = 32;
		filtn = H5Pget_filter1(pid, (unsigned)i, 
			&filt_flags, &cd_nelmts, cd_values, 
			(size_t)MAX_NAME, f_name);
  		/* 
                 **  These are the predefined filters 
                 **/
		switch (filtn) {
			case H5Z_FILTER_DEFLATE:  /* AKA GZIP compression */
				printf("DEFLATE level = %d\n", cd_values[0]);
				break;
			case H5Z_FILTER_SHUFFLE:
				printf("SHUFFLE\n"); /* no parms */
				break;
		       case H5Z_FILTER_FLETCHER32:
				printf("FLETCHER32\n"); /* Error Detection Code */
				break;
		       case H5Z_FILTER_SZIP:
				szip_options_mask=cd_values[0];;
				szip_pixels_per_block=cd_values[1];

				printf("SZIP COMPRESSION: ");
				printf("PIXELS_PER_BLOCK %d\n", 
					szip_pixels_per_block);
				 /* print SZIP options mask, etc. */
				break;
			default:
				printf("UNKNOWN_FILTER\n" );
				break;
	       }
      }

	/* 
         **  Get the fill value information when to allocate space on disk
         **    - when to fill on disk
         **    - value to fill, if any
         **/
	printf("ALLOC_TIME ");
	H5Pget_alloc_time(pid, &at);

	switch (at) 
	{
		case H5D_ALLOC_TIME_EARLY: 
			printf("EARLY\n");
			break;
		case H5D_ALLOC_TIME_INCR:
			printf("INCR\n");
			break;
		case H5D_ALLOC_TIME_LATE: 
			printf("LATE\n");
			break;
		default:
			printf("unknown allocation policy");
			break;
	}

	printf("FILL_TIME: ");
	H5Pget_fill_time(pid, &ft);
	switch ( ft ) 
	{
		case H5D_FILL_TIME_ALLOC: 
			printf("ALLOC\n");
			break;
		case H5D_FILL_TIME_NEVER: 
			printf("NEVER\n");
			break;
		case H5D_FILL_TIME_IFSET: 
			printf("IFSET\n");
			break;
		default:
			printf("?\n");
		break;
	}


	H5Pfill_value_defined(pid, &fvstatus);
 
	if (fvstatus == H5D_FILL_VALUE_UNDEFINED) 
	{
		printf("No fill value defined, will use default\n");
	} else {
		/* Read  the fill value with H5Pget_fill_value. 
                 ** Fill value is the same data type as the dataset.
                 ** (detailse not shown) 
                 **/
	}
	/* ... and so on for other dataset properties ... */
}
