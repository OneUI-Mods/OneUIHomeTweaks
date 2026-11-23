cmake_minimum_required(VERSION 3.22.1)
project(oneui_icon_upload C)

add_library(oneui_icon_upload SHARED launcher_icon_upload.c)
target_compile_options(oneui_icon_upload PRIVATE -Wall -Wextra -Werror)
target_link_libraries(oneui_icon_upload PRIVATE android)
