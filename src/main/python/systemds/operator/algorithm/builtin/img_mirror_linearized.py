# -------------------------------------------------------------
#
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
#
# -------------------------------------------------------------

# Autogenerated By   : src/main/python/generator/generator.py
# Autogenerated From : scripts/builtin/img_mirror_linearized.dml

from typing import Dict, Iterable

from systemds.operator import OperationNode, Matrix, Frame, List, MultiReturn, Scalar
from systemds.script_building.dag import OutputType
from systemds.utils.consts import VALID_INPUT_TYPES


def img_mirror_linearized(img_matrix: Matrix,
                          horizontal_axis: bool,
                          original_rows: int,
                          original_cols: int):
    """
     This function has  the same functionality with img_mirror but it handles multiple images at
     the same time. Each row of the input and output matrix represents a linearized image/matrix
     It flips an image on the X (horizontal) or Y (vertical) axis.
    
    
    :param img_matrix: Input matrix/image (every row represents a linearized matrix/image)
    :param horizontal_axis: flip either in X or Y axis
    :param original_rows: number of rows in the original 2-D images
    :param original_cols: number of cols in the original 2-D images
    :return: Output matrix/image  (every row represents a linearized matrix/image)
    """

    params_dict = {'img_matrix': img_matrix, 'horizontal_axis': horizontal_axis, 'original_rows': original_rows, 'original_cols': original_cols}
    return Matrix(img_matrix.sds_context,
        'img_mirror_linearized',
        named_input_nodes=params_dict)