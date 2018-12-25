#include "string"
#include "stdio.h"
#include "stdlib.h"
#include "vector"
#include <iostream>
#include "fstream"
#include "mpi.h"
#include <unistd.h> 
#include <omp.h> 

int main(int argc, char *argv[]) {
  MPI_Init(&argc, &argv);
  int size;
  int rank;
  MPI_Comm_size(MPI_COMM_WORLD, &size);
  MPI_Comm_rank(MPI_COMM_WORLD, &rank);
  printf("Init MPI with %d threads\n",size);
  if (rank == 0) {
    std::vector<std::string> buf;
    std::string s;
    std::ifstream f("path.log");
    /*
    #pragma omp parallel
    printf("This is 1\n");
    */
    omp_set_num_threads(8);
    int threads = omp_get_num_procs();
    printf("Num of omp threads is %d\n", threads);
    #pragma omp parallel for
    for (int i = 0; i < 5; i++) {
      printf("round %d\n", i);
      sleep(3);
    }
    while(f >> s) {
      std::cout << "Read from file: " << s << std::endl;
      buf.push_back(s);
    }
    printf("Num of string is %d\n", buf.size());
    //sent msg to slave
    //vector<std::string>::iterator it = buf.begin();
    for (int i = 0; i < size; i++) {
      s = buf[0];
      int length = s.length();
      printf("length of string is %d\n",length);
      const char *tmp = s.data();
      printf("Path : %s\n", tmp);   
    }

  }

  MPI_Finalize();
  return 0;
}
