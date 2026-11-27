cmake_minimum_required(VERSION 3.22.1)
project(atv_move C)
add_library(atv_move SHARED atomic_move.c)
target_link_options(atv_move PRIVATE "-Wl,-z,max-page-size=16384")
